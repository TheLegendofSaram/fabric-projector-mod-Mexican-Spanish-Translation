package com.github.hashicraft.projector.downloader;

import static java.util.concurrent.Executors.newFixedThreadPool;

import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import com.github.hashicraft.projector.ProjectorMod;

import io.netty.handler.timeout.TimeoutException;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.util.Identifier;

public class FileDownloader {

  public class PictureData {
    public int height = 0;
    public int width = 0;
    public String location = "";
    public Identifier identifier = null;
    public Instant created = null;
    public Instant started = null;
    public int cacheSeconds = 0;

    public PictureData(String location, int cacheSeconds) {
      this.location = location;
      this.cacheSeconds = cacheSeconds;
    }
  }

  static FileDownloader downloader = new FileDownloader();

  public static FileDownloader getInstance() {
    return downloader;
  }

  // Ensure that no more than 4 download threads operate at once
  private ExecutorService downloadService = newFixedThreadPool(4);

  // background thread service
  private ExecutorService service = new ThreadPoolExecutor(4, 1000, 0L, TimeUnit.MILLISECONDS,
      new LinkedBlockingQueue<Runnable>());

  // stores a cache of pictures
  private Hashtable<String, PictureData> cache = new Hashtable<String, PictureData>();
  private ArrayList<Identifier> recycle = new ArrayList<Identifier>();

  private Object mutex = new Object();
  private Object cacheMutex = new Object();

  // reap removes any recycled identifiers
  public void reap() {
    synchronized (cacheMutex) {
      // only recycle when we have a decent size
      if (recycle.size() > 20) {
        ProjectorMod.LOGGER.info("Reaping cache");

        TextureManager tm = MinecraftClient.getInstance().getTextureManager();
        for (Identifier i : recycle) {
          ProjectorMod.LOGGER.info("destroy texture: " + i.toString());
          tm.destroyTexture(i);
        }

        recycle.clear();
      }
    }
  }

  // Asyncronously download an image from the given URL
  public void download(String url, int cacheSeconds) {
    synchronized (mutex) {
      PictureData data = this.cache.get(url);
      if (data == null) {
        this.downloadFile(url, cacheSeconds);
      }
    }
  }

  // Gets the picture data for the given URL, if a picture for the URL
  // does not exist in the cache and download is true getPictureDataForURL
  // will add the given URL to the download queue.
  //
  // It is safe to call this method in a loop as URL is only ever downloaded
  // once.
  // If cacheSeconds is greater than 0 the image is re-downloaded n seconds after
  // the initial download
  public PictureData getPictureDataForURL(String url, Boolean download, int cacheSeconds) {
    synchronized (mutex) {
      // attempt to get the url from the cache
      PictureData data = this.cache.get(url);
      // image does not exist so add to the queue
      if (data == null && download) {
        // add to the queue
        this.downloadFile(url, cacheSeconds);
        return null;
      }

      // check the cache, if the cache seconds is greater than 0 and the image has
      // already been downloaded
      if (data != null && data.identifier != null && cacheSeconds > 0 && data.created != null) {
        // ProjectorMod.LOGGER.info("Cache expired for url:" + url + " cacheSeconds: " +
        // cacheSeconds +
        // " created: " + data.created);
        Instant timeNow = Instant.now();
        long life = Duration.between(data.created, timeNow).toSeconds();
        if (life > cacheSeconds) {
          ProjectorMod.LOGGER
              .info("Cache expired for url:" + url + " cacheSeconds: " + cacheSeconds + " lifespan: " + life);

          // add to the queue and redownload
          data.created = null;
          data.started = null;
          this.downloadFile(url, cacheSeconds);
        }
      }

      // if no identifier exists then the image has not completed downloading
      if (data.identifier == null) {
        return null;
      }

      return data;
    }

  }

  // downloads the file as a background process
  private void downloadFile(String location, int cacheSeconds) {
    ProjectorMod.LOGGER.info("Starting download for:" + location);

    // is already in process?
    PictureData cd = this.cache.get(location);
    if (cd != null) {
      // check if download started
      if (cd.started != null) {
        return;
      }

      cd.started = Instant.now();
    } else {
      PictureData newP = new PictureData(location, cacheSeconds);
      // add the url to the cache, we can update this once download is complete
      newP.started = Instant.now();
      this.cache.put(location, newP);
    }

    // run in the background
    service.submit(() -> {
      // retry the download in case the file is not currently avaialble
      RetryPolicy<Object> retryPolicy = new RetryPolicy<>()
          .withBackoff(3, 60, ChronoUnit.SECONDS)
          .withMaxAttempts(3)
          .withMaxDuration(Duration.ofMinutes(10))
          .handle(RejectedExecutionException.class)
          .onRetry(e -> ProjectorMod.LOGGER.info("Unable to download, retrying: " + e.getLastFailure()))
          .onRetriesExceeded(e -> {
            ProjectorMod.LOGGER.info("Unable to download, aborting " + e.getFailure());

            // delete from the cache
            cache.remove(location);
          });

      Failsafe.with(retryPolicy).run(() -> {
        try {
          boolean isURL = false;

          final File tmpFile = File.createTempFile("image", "");
          tmpFile.deleteOnExit();

          File file = null;

          // Are we dealing with a local file or URL?
          try {
            final URL url = new URL(location);

            if (url.getProtocol().equals("http") || url.getProtocol().equals("https")) {
              isURL = true;
            }

            // We need to ensure that download threads do not overwhelm the system so we
            // limit
            // downloads to 10 concurrent threads
            Future<?> future = downloadService.submit(() -> {
              try {

                final HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("user-agent",
                    " curl/7.81.0");
                conn.setRequestProperty("accept", "*/*");

                BufferedInputStream in = new BufferedInputStream(conn.getInputStream());
                BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(tmpFile));

                byte dataBuffer[] = new byte[1024];
                int bytesRead;
                while ((bytesRead = in.read(dataBuffer, 0, 1024)) != -1) {
                  out.write(dataBuffer, 0, bytesRead);
                }

                out.close();
                in.close();
              } catch (Exception ex) {
              }
            });

            synchronized (future) {
              // wait 60s for the download to complete before quiting
              future.get(60L, TimeUnit.SECONDS);
            }
          } catch (MalformedURLException me) {
            isURL = false;
          } catch (TimeoutException e) {
            throw new FileNotFoundException("Timeout attempting to download file at url: " + e);
          }

          // not a URL check if is a local file
          if (isURL) {
            file = tmpFile;
          } else {
            file = new File(location);
            if (!file.exists()) {
              ProjectorMod.LOGGER.info("Unable to load file: " + location);
              throw new FileNotFoundException("File not found: " + location);
            }
          }

          // convert to a png
          BufferedImage bufferedImage = ImageIO.read(file);

          ByteArrayOutputStream byteArrayOut = new ByteArrayOutputStream();
          ImageIO.write(bufferedImage, "png", byteArrayOut);

          // byte[] resultingBytes = byteArrayOut.toByteArray();
          // ImageIO.write(bufferedImage, "png", new File(outputFile));
          InputStream targetStream = new ByteArrayInputStream(byteArrayOut.toByteArray());

          NativeImage nativeImage = NativeImage.read(targetStream);
          NativeImageBackedTexture nativeTexture = new NativeImageBackedTexture(nativeImage);
          TextureManager tm = MinecraftClient.getInstance().getTextureManager();
          Identifier id = tm.registerDynamicTexture("image/pictures",
              nativeTexture);

          // update the cache
          synchronized (mutex) {
            PictureData data = this.cache.get(location);

            // if we have an existing image add it to the recycle list
            if (data.identifier != null) {
              synchronized (cacheMutex) {
                recycle.add(data.identifier);
              }
            }

            data.identifier = id;
            data.height = bufferedImage.getHeight();
            data.width = bufferedImage.getWidth();
            data.created = Instant.now();
          }

          ProjectorMod.LOGGER.info("Downloaded url: " + location);
        } catch (Exception ex) {
          throw new RejectedExecutionException("Unable to download file " + ex);
        }
      });
    });
  }
}