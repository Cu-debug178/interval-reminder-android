package com.example.reviewreminder;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Offline-only private storage for immersive card background and bottom decoration images.
 * Reads via system photo picker / SAF URIs without broad storage permissions, corrects EXIF
 * orientation, downsamples safely to bound memory, and writes atomically under {@code files/card-style/}.
 */
final class CardStyleImages {
    private static final String DIR = "card-style";
    static final int MAX_BG_SIDE = 1440;
    static final int MAX_BOTTOM_SIDE = 960;
    private static final long MAX_IMPORT_BYTES = 24L * 1024 * 1024;
    private static final long MAX_BACKUP_BYTES = 8L * 1024 * 1024;

    private static final Object CACHE_LOCK = new Object();
    private static String cachedBgName = "", cachedBottomName = "";
    private static Bitmap cachedBgBitmap, cachedBottomBitmap;

    private CardStyleImages() {}

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), DIR);
        if (!d.exists() && !d.mkdirs()) throw new IllegalStateException("无法创建卡片图片目录");
        return d;
    }

    static File file(Context c, String relative) {
        String safe = CardStyle.sanitizeImageName(relative);
        if (safe.isEmpty()) return null;
        File target = new File(dir(c), safe);
        return safe.equals(target.getName()) ? target : null;
    }

    static boolean exists(Context c, String relative) {
        File f = file(c, relative);
        return f != null && f.isFile() && f.length() > 0;
    }

    /**
     * Imports a user-picked image into private app storage with EXIF rotation fix and downsampling.
     * {@code prefix} should be {@code "bg_"} or {@code "bt_"}.
     */
    static String importUri(Context c, Uri uri, String prefix, int maxSide) throws IOException {
        if (uri == null) throw new IOException("未选择图片");
        File tmpSrc = File.createTempFile("card_pick_", ".tmp", c.getCacheDir());
        try {
            copyLimited(c, uri, tmpSrc);
            int orientation = readOrientation(tmpSrc);
            Bitmap decoded = decodeDownsampled(tmpSrc, maxSide);
            if (decoded == null) throw new IOException("无法解码所选图片，请更换常见 JPG/PNG/WebP 图片");
            Bitmap finalBitmap = decoded;
            try {
                Bitmap oriented = applyOrientation(decoded, orientation);
                if (oriented != decoded) {
                    decoded.recycle();
                    finalBitmap = oriented;
                }
                Bitmap scaled = scaleDownIfNeeded(finalBitmap, maxSide);
                if (scaled != finalBitmap) {
                    finalBitmap.recycle();
                    finalBitmap = scaled;
                }
                return saveJpeg(c, prefix, finalBitmap);
            } finally {
                if (finalBitmap != null && !finalBitmap.isRecycled()) finalBitmap.recycle();
            }
        } finally {
            tmpSrc.delete();
        }
    }

    static Bitmap decodeCached(Context c, String name, boolean isBackground) {
        String safe = CardStyle.sanitizeImageName(name);
        if (safe.isEmpty()) return null;
        synchronized (CACHE_LOCK) {
            if (isBackground && safe.equals(cachedBgName) && cachedBgBitmap != null && !cachedBgBitmap.isRecycled()) {
                return cachedBgBitmap;
            }
            if (!isBackground && safe.equals(cachedBottomName) && cachedBottomBitmap != null && !cachedBottomBitmap.isRecycled()) {
                return cachedBottomBitmap;
            }
        }
        File f = file(c, safe);
        if (f == null || !f.isFile()) return null;
        try {
            Bitmap bm = decodeDownsampled(f, isBackground ? MAX_BG_SIDE : MAX_BOTTOM_SIDE);
            if (bm == null) return null;
            synchronized (CACHE_LOCK) {
                if (isBackground) {
                    cachedBgName = safe;
                    cachedBgBitmap = bm;
                } else {
                    cachedBottomName = safe;
                    cachedBottomBitmap = bm;
                }
            }
            return bm;
        } catch (Throwable ignored) {
            return null;
        }
    }

    static void clearMemoryCache() {
        synchronized (CACHE_LOCK) {
            cachedBgName = "";
            cachedBottomName = "";
            cachedBgBitmap = null;
            cachedBottomBitmap = null;
        }
    }

    static void delete(Context c, String relative) {
        File f = file(c, relative);
        if (f != null && f.exists()) f.delete();
        synchronized (CACHE_LOCK) {
            if (relative != null && relative.equals(cachedBgName)) {
                cachedBgName = "";
                cachedBgBitmap = null;
            }
            if (relative != null && relative.equals(cachedBottomName)) {
                cachedBottomName = "";
                cachedBottomBitmap = null;
            }
        }
    }

    /** Deletes any private card-style images not referenced by {@code keepStyle} or {@code extraKeep}. */
    static void pruneUnused(Context c, CardStyle keepStyle, String... extraKeep) {
        Set<String> keep = new HashSet<>();
        if (keepStyle != null) {
            if (!keepStyle.backgroundImage.isEmpty()) keep.add(keepStyle.backgroundImage);
            if (!keepStyle.bottomImage.isEmpty()) keep.add(keepStyle.bottomImage);
        }
        if (extraKeep != null) {
            for (String name : extraKeep) {
                String safe = CardStyle.sanitizeImageName(name);
                if (!safe.isEmpty()) keep.add(safe);
            }
        }
        File d = new File(c.getFilesDir(), DIR);
        File[] files = d.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isFile() && !keep.contains(f.getName())) {
                f.delete();
            }
        }
    }

    static String base64(Context c, String relative) throws IOException {
        File f = file(c, relative);
        if (f == null || !f.isFile()) throw new IOException("卡片图片已丢失");
        if (f.length() > MAX_BACKUP_BYTES) throw new IOException("卡片图片过大，无法写入备份");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
        return Base64Compat.encode(out.toByteArray());
    }

    static ConnectionImages.StagedFile stage(Context c, String relative, String encoded) throws IOException {
        File target = file(c, relative);
        if (target == null) throw new IOException("卡片图片文件名无效");
        byte[] bytes = Base64Compat.decode(encoded);
        if (bytes.length == 0) throw new IOException("卡片图片内容为空");
        File tmp = new File(target.getParentFile(), target.getName() + ".part");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(bytes);
            out.getFD().sync();
        }
        return new ConnectionImages.StagedFile(tmp, target);
    }

    private static void copyLimited(Context c, Uri uri, File dest) throws IOException {
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("无法读取所选图片");
            try (FileOutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[8192];
                long total = 0L;
                int n;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > MAX_IMPORT_BYTES) throw new IOException("图片文件过大（上限 24 MB），请选择较小图片");
                    out.write(buf, 0, n);
                }
                out.flush();
            }
        }
    }

    private static int readOrientation(File file) {
        try {
            ExifInterface exif = new ExifInterface(file.getAbsolutePath());
            return exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (Throwable ignored) {
            return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    private static Bitmap decodeDownsampled(File file, int maxSide) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("所选文件不是有效图片");
        }
        int sample = 1;
        int longest = Math.max(bounds.outWidth, bounds.outHeight);
        while (maxSide > 0 && longest / sample > maxSide) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = Math.max(1, sample);
        opts.inPreferredConfig = Bitmap.Config.RGB_565;
        try {
            return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        } catch (OutOfMemoryError oom) {
            opts.inSampleSize = Math.max(2, sample * 2);
            return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        }
    }

    private static Bitmap applyOrientation(Bitmap src, int orientation) {
        Matrix m = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90: m.postRotate(90); break;
            case ExifInterface.ORIENTATION_ROTATE_180: m.postRotate(180); break;
            case ExifInterface.ORIENTATION_ROTATE_270: m.postRotate(270); break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: m.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL: m.postScale(1, -1); break;
            case ExifInterface.ORIENTATION_TRANSPOSE: m.postRotate(90); m.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_TRANSVERSE: m.postRotate(270); m.postScale(-1, 1); break;
            default: return src;
        }
        try {
            return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
        } catch (OutOfMemoryError ignored) {
            return src;
        }
    }

    private static Bitmap scaleDownIfNeeded(Bitmap src, int maxSide) {
        int w = src.getWidth(), h = src.getHeight();
        int longest = Math.max(w, h);
        if (maxSide <= 0 || longest <= maxSide) return src;
        float ratio = maxSide / (float) longest;
        int nw = Math.max(1, Math.round(w * ratio));
        int nh = Math.max(1, Math.round(h * ratio));
        try {
            return Bitmap.createScaledBitmap(src, nw, nh, true);
        } catch (OutOfMemoryError ignored) {
            return src;
        }
    }

    private static String saveJpeg(Context c, String prefix, Bitmap bitmap) throws IOException {
        if (bitmap == null || bitmap.getWidth() < 1 || bitmap.getHeight() < 1) {
            throw new IOException("图片内容为空");
        }
        String safePrefix = "bt_".equals(prefix) ? "bt_" : "bg_";
        String name = safePrefix + UUID.randomUUID().toString().replace("-", "") + ".jpg";
        File target = new File(dir(c), name);
        File tmp = new File(target.getParentFile(), name + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 84, out)) {
                throw new IOException("图片压缩保存失败");
            }
            out.getFD().sync();
        } catch (IOException e) {
            tmp.delete();
            throw e;
        }
        if (!tmp.renameTo(target)) {
            tmp.delete();
            throw new IOException("图片未能写入私有目录");
        }
        return name;
    }
}
