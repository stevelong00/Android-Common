package in.uncod.android.graphics;

import in.uncod.android.Util;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.concurrent.ConcurrentLinkedQueue;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.widget.ImageView;

public class BitmapManager {
    public static final double MIN_MEMORY_FACTOR = .125;
    public static final double MAX_MEMORY_FACTOR = .5;

    /**
     * Interface for informing objects of the image loading process
     */
    public interface OnBitmapLoadedListener {
        /**
         * Called when an image is finished loading
         * 
         * Note: not guaranteed to execute on the UI thread
         * 
         * @param cached
         *            true if the image was available in the cache
         */
        void onImageLoaded(boolean cached);

        /**
         * Called before the loading process is started for an image
         * 
         * Note: not guaranteed to execute on the UI thread
         * 
         * @param cached
         *            true if the image is available in the cache
         */
        void beforeImageLoaded(boolean cached);
    }

    private static BitmapManager instance;
    private static double mMemoryFactor;
    private ImageCache mCache;
    private ConcurrentLinkedQueue<Image> mQueue = new ConcurrentLinkedQueue<Image>();
    private BitmapLoader mBitmapLoader;

    /**
     * Gets a BitmapManager with a memory factor of at least 1/8.
     * 
     * If a previous BitmapManager was created with a larger memory factor, it will be retrieved instead.
     * 
     * @param context
     *            The Context to associate with the BitmapManager
     * 
     * @return A BitmapManager instance
     */
    public static BitmapManager get(Context context) {
        return get(context, MIN_MEMORY_FACTOR);
    }

    /**
     * Gets a BitmapManager with a minimum specified memory factor
     * 
     * @param context
     *            The context to associate with the BitmapManager
     * @param memoryFactor
     *            The portion of memory the cache will be allowed to allocate. Valid values are in the range [.125, .5].
     *            The BitmapManager instance with the largest requested memory factor is retained and will be returned
     *            unless a larger memory factor is specified.
     * 
     * @return A BitmapManager instance
     */
    public static BitmapManager get(Context context, double memoryFactor) {
        if (memoryFactor > MAX_MEMORY_FACTOR) {
            memoryFactor = MAX_MEMORY_FACTOR;
        }
        else if (memoryFactor < MIN_MEMORY_FACTOR) {
            memoryFactor = MIN_MEMORY_FACTOR;
        }

        if (mMemoryFactor < memoryFactor) {
            mMemoryFactor = memoryFactor;

            instance = new BitmapManager(context, memoryFactor);
        }

        return instance;
    }

    private BitmapManager(Context context, double memoryFactor) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);

        int memoryClassBytes = am.getMemoryClass() * 1024 * 1024;
        mCache = new ImageCache((int) (memoryClassBytes * memoryFactor));
    }

    /**
     * Loads and scales the specified Bitmap image into an ImageView on the given Activity.
     * 
     * @param imageFilename
     *            The location of the bitmap on the filesystem
     * @param activity
     *            The Activity that contains the destination ImageView
     * @param imageView
     *            The ImageView that will display the image
     * @param maxSize
     *            Specifies the maximum width or height of the image. Images that exceed this size in either dimension
     *            will be scaled down, with their aspect ratio preserved. If -1, the image will not be scaled at all.
     */
    public void displayBitmapScaled(String imageFilename, final Activity activity, ImageView imageView,
            int maxSize) {
        displayBitmapScaled(imageFilename, activity, imageView, maxSize, null);
    }

    /**
     * Loads and scales the specified Bitmap image into an ImageView on the given Activity.
     * 
     * @param imageFilename
     *            The location of the bitmap on the filesystem
     * @param activity
     *            The Activity that contains the destination ImageView
     * @param imageView
     *            The ImageView that will display the image
     * @param maxSize
     *            Specifies the maximum width or height of the image. Images that exceed this size in either dimension
     *            will be scaled down, with their aspect ratio preserved. If -1, the image will not be scaled at all.
     * @param bitmapLoadedListener
     *            If not null, this listener will be notified after the image bitmap is updated
     */
    public void displayBitmapScaled(String imageFilename, final Activity activity, ImageView imageView,
            int maxSize, OnBitmapLoadedListener bitmapLoadedListener) {
        if (imageFilename == null || imageFilename.equals(""))
            throw new IllegalArgumentException("imageFilename must be specified");

        if (!new File(imageFilename).exists()) {
            throw new IllegalArgumentException("imageFilename must be a real file");
        }

        Image image = new Image(activity, imageFilename, imageView, maxSize, bitmapLoadedListener);

        // Have the ImageView remember the latest image to display
        imageView.setTag(image.getHash());

        Bitmap cachedResult = mCache.get(image.getHash());
        if (cachedResult != null) {
            // Notify listener
            if (bitmapLoadedListener != null) {
                bitmapLoadedListener.beforeImageLoaded(true);
            }

            setImage(image);

            // Notify listener
            if (bitmapLoadedListener != null) {
                bitmapLoadedListener.onImageLoaded(true);
            }
        }
        else {
            // Notify listener
            if (bitmapLoadedListener != null) {
                bitmapLoadedListener.beforeImageLoaded(false);
            }

            mQueue.add(image);
            if (mBitmapLoader == null || !mBitmapLoader.isAlive()) {
                mBitmapLoader = new BitmapLoader();
                mBitmapLoader.start();
            }
        }
    }

    private void setImage(final Image image) {
        final Bitmap bitmap = mCache.get(image.getHash());
        if (bitmap != null) {
            image.getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    image.getImageView().setImageBitmap(bitmap);
                }
            });
        }
    }

    // http://stackoverflow.com/a/3549021/136408
    public static Bitmap loadBitmapScaled(File f, int maxSize) {
        Bitmap b = null;
        try {
            // Decode image size
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;

            FileInputStream fis = new FileInputStream(f);
            BitmapFactory.decodeStream(fis, null, o);
            fis.close();

            int scale = 1;
            if (o.outHeight > maxSize || o.outWidth > maxSize) {
                scale = (int) Math.pow(
                        2,
                        (int) Math.round(Math.log(maxSize / (double) Math.max(o.outHeight, o.outWidth))
                                / Math.log(0.5)));
            }

            // Decode with inSampleSize
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = scale;
            fis = new FileInputStream(f);
            b = BitmapFactory.decodeStream(fis, null, o2);
            fis.close();
        }
        catch (IOException e) {
        }
        return b;
    }

    private class Image {
        private File imageLocation;
        private String hash;
        private ImageView imageView;
        private Activity activity;
        private int maxSize;
        private OnBitmapLoadedListener runnable;

        public Image(Activity activity, String imageLocation, ImageView imageView, int maxSize,
                OnBitmapLoadedListener runAfterImageUpdated) {
            this.imageLocation = new File(imageLocation);
            this.hash = Util.md5(imageLocation + maxSize);
            this.imageView = imageView;
            this.activity = activity;
            this.maxSize = maxSize;
            this.runnable = runAfterImageUpdated;
        }

        public OnBitmapLoadedListener getListener() {
            return runnable;
        }

        public File getImageLocation() {
            return imageLocation;
        }

        public String getHash() {
            return hash;
        }

        public ImageView getImageView() {
            return imageView;
        }

        public Activity getActivity() {
            return activity;
        }

        public int getMaxSize() {
            return maxSize;
        }
    }

    private class BitmapLoader extends Thread {
        @Override
        public void run() {
            while (!mQueue.isEmpty()) {
                Image image = mQueue.poll();

                if (!image.getImageView().getTag().equals(image.getHash()))
                    continue; // Don't bother loading image since we don't want it in this view anymore

                Bitmap b;

                if (image.getMaxSize() == -1) {
                    b = BitmapFactory.decodeFile(image.getImageLocation().getAbsolutePath());
                }
                else {
                    b = loadBitmapScaled(image.getImageLocation(), image.getMaxSize());
                }

                if (image.getHash() != null && b != null) {
                    mCache.put(image.getHash(), b);
                }

                setImage(image);

                // Notify listener
                if (image.getListener() != null) {
                    image.getListener().onImageLoaded(false);
                }
            }
        }
    }
}
