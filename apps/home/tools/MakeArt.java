import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.TexturePaint;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * Generator for the launcher banner and icon, drawn on the bundled graphite wallpaper.
 * Output is checked in, not run by build.sh. Fonts differ per OS, so the result can differ slightly.
 * Usage: java -Djava.awt.headless=true tools/MakeArt.java res
 */
public class MakeArt {
    static final Color INK = new Color(0xEEF0F3);
    static final String[] MEDIUM = {"HelveticaNeue-Medium", "Roboto-Medium", "Segoe UI Semibold", "DejaVuSans"};
    static final String[] LIGHT = {"HelveticaNeue-Light", "Roboto-Light", "Segoe UI Light", "DejaVuSans-ExtraLight"};

    public static void main(String[] args) throws IOException {
        File res = new File(args[0]);
        BufferedImage graphite = ImageIO.read(new File(res, "drawable-nodpi/graphite.png"));
        ImageIO.write(banner(graphite), "png", new File(res, "drawable-xhdpi/banner.png"));
        ImageIO.write(icon(graphite), "png", new File(res, "mipmap-xhdpi/ic_launcher.png"));
    }

    /** First installed face of the list; the JDK's sans-serif if none is. */
    static Font face(String[] names, float size) {
        for (String name : names) {
            Font f = new Font(name, Font.PLAIN, 1);
            if (!Font.DIALOG.equals(f.getFamily())) {
                return f.deriveFont(size);
            }
        }
        System.err.println("none of " + String.join(", ", names) + " installed; using SansSerif");
        return new Font(Font.SANS_SERIF, Font.PLAIN, 1).deriveFont(size);
    }

    static Graphics2D smooth(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        return g;
    }

    static Image scaled(BufferedImage src, int x, int y, int w, int h, int outW, int outH) {
        return src.getSubimage(x, y, w, h).getScaledInstance(outW, outH, Image.SCALE_AREA_AVERAGING);
    }

    /** "Open" in medium, "Shelf" in light, bottom left, like a caption on the wallpaper. */
    static BufferedImage banner(BufferedImage graphite) {
        BufferedImage img = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = smooth(img);
        g.drawImage(scaled(graphite, 0, 0, graphite.getWidth(), graphite.getHeight(), 320, 180), 0, 0, null);
        g.setColor(INK);
        Font medium = face(MEDIUM, 28f);
        g.setFont(medium);
        FontMetrics fm = g.getFontMetrics();
        g.drawString("Open", 24, 140);
        int x = 24 + fm.stringWidth("Open");
        g.setFont(face(LIGHT, 28f));
        g.drawString("Shelf", x, 140);
        g.dispose();
        return img;
    }

    /** Rounded tile cut from the middle of the wallpaper with a light "O". */
    static BufferedImage icon(BufferedImage graphite) {
        BufferedImage img = new BufferedImage(96, 96, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = smooth(img);
        int side = graphite.getHeight();
        BufferedImage tile = new BufferedImage(88, 88, BufferedImage.TYPE_INT_RGB);
        Graphics2D tg = tile.createGraphics();
        tg.drawImage(scaled(graphite, (graphite.getWidth() - side) / 2, 0, side, side, 88, 88), 0, 0, null);
        tg.dispose();
        g.setPaint(new TexturePaint(tile, new Rectangle(4, 4, 88, 88)));
        g.fill(new RoundRectangle2D.Double(4, 4, 88, 88, 20, 20));
        g.setColor(INK);
        g.setFont(face(LIGHT, 58f));
        FontMetrics fm = g.getFontMetrics();
        int w = fm.stringWidth("O");
        int capHeight = (int) Math.round(g.getFont().createGlyphVector(g.getFontRenderContext(), "O")
                .getVisualBounds().getHeight());
        g.drawString("O", (96 - w) / 2, (96 + capHeight) / 2);
        g.dispose();
        return img;
    }
}
