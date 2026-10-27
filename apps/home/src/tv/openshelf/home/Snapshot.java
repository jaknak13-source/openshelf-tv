package tv.openshelf.home;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The rows as last shown, plus their rendered faces, kept in app storage. A cold start shows
 * this first (a few small PNG reads) and then reconciles with the live catalog; unchanged tiles
 * keep their view and face, so the swap is invisible. Worker thread only.
 */
final class Snapshot {
    private static final String TAG = Catalog.TAG;
    private static final String HEADER = "openshelf-home-snapshot 1";

    /** One row entry with the face size it was rendered at. */
    static final class Item {
        final int row;
        final Entry entry;
        final int faceW;
        final int faceH;
        final int tint;

        Item(int row, Entry entry, int faceW, int faceH, int tint) {
            this.row = row;
            this.entry = entry;
            this.faceW = faceW;
            this.faceH = faceH;
            this.tint = tint;
        }
    }

    private final File file;
    private final File faces;

    Snapshot(Context c) {
        file = new File(c.getFilesDir(), "snapshot.txt");
        faces = new File(c.getCacheDir(), "faces");
    }

    /** Reads the snapshot and puts every face into the art cache. Null if missing or stale. */
    List<Item> load(Art art) {
        if (!file.exists()) return null;
        List<Item> items = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            if (!HEADER.equals(r.readLine())) return null;
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.split("\t", -1);
                if (f.length != 11) return null;
                Intent intent = f[10].isEmpty() ? null : Intent.parseUri(f[10], 0);
                Entry e = new Entry(f[1], Integer.parseInt(f[2]), f[3], f[4].isEmpty() ? null : f[4],
                        intent, null, Long.parseLong(f[6]), "1".equals(f[5]));
                Item item = new Item(Integer.parseInt(f[0]), e, Integer.parseInt(f[7]), Integer.parseInt(f[8]),
                        Integer.parseInt(f[9]));
                Bitmap b = BitmapFactory.decodeFile(faceFile(e, item.faceW, item.faceH).getPath());
                if (b == null) return null; // incomplete snapshot: better nothing than holes
                art.put(e, item.faceW, item.faceH, new Art.Face(b, item.tint));
                items.add(item);
            }
        } catch (IOException | URISyntaxException | NumberFormatException e) {
            Log.w(TAG, "snapshot unreadable, ignoring", e);
            return null;
        }
        return items;
    }

    void save(List<Item> items, Art art) {
        if (!faces.isDirectory() && !faces.mkdirs()) return;
        Set<String> keep = new HashSet<>();
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        for (Item it : items) {
            Entry e = it.entry;
            Art.Face face = art.cached(e, it.faceW, it.faceH);
            if (face == null) return; // not all faces rendered yet; a later save will cover it
            File out = faceFile(e, it.faceW, it.faceH);
            keep.add(out.getName());
            if (!out.exists() && !writePng(face.bitmap, out)) return;
            sb.append(it.row).append('\t').append(clean(e.key)).append('\t').append(e.kind).append('\t')
                    .append(clean(e.label)).append('\t').append(e.detail == null ? "" : clean(e.detail)).append('\t')
                    .append(e.dimmed ? '1' : '0').append('\t').append(e.stamp).append('\t')
                    .append(it.faceW).append('\t').append(it.faceH).append('\t').append(face.tint).append('\t')
                    .append(e.intent == null ? "" : e.intent.toUri(0)).append('\n');
        }
        File tmp = new File(file.getPath() + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            w.write(sb.toString());
        } catch (IOException e) {
            Log.w(TAG, "snapshot not saved", e);
            return;
        }
        if (!tmp.renameTo(file)) return;
        File[] old = faces.listFiles();
        if (old != null) {
            for (File f : old) {
                if (!keep.contains(f.getName())) f.delete();
            }
        }
    }

    private File faceFile(Entry e, int w, int h) {
        String k = Art.cacheKey(e, w, h);
        return new File(faces, Integer.toHexString(k.hashCode()) + "_" + k.length() + ".png");
    }

    private static boolean writePng(Bitmap b, File out) {
        File tmp = new File(out.getPath() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            if (!b.compress(Bitmap.CompressFormat.PNG, 100, fos)) return false;
        } catch (IOException e) {
            Log.w(TAG, "face not saved: " + out, e);
            return false;
        }
        return tmp.renameTo(out);
    }

    private static String clean(String s) {
        return s.replace('\t', ' ').replace('\n', ' ');
    }
}
