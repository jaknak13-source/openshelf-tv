import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * Generator for the launcher banner and icon (output is checked in, not run by build.sh).
 * Fonts differ per OS, so the result can differ slightly.
 * Usage: java -Djava.awt.headless=true tools/MakeArt.java res
 */
public class MakeArt {
    static final Color BG = new Color(0x0F1114);
    static final Color SURFACE = new Color(0x1C2027);
    static final Color FG = new Color(0xECEEF1);
    static final Color MUTED = new Color(0x9AA3AD);
    static final Color ACCENT = new Color(0x8FD19E);
    static final String[] BOLD = {"HelveticaNeue-Bold", "Roboto-Bold", "Segoe UI Bold", "DejaVuSans-Bold"};
    static final String[] REGULAR = {"HelveticaNeue", "Roboto-Regular", "Segoe UI", "DejaVuSans"};

    public static void main(String[] args) throws IOException {
        File res = new File(args[0]);
        ImageIO.write(banner(), "png", new File(res, "drawable-xhdpi/banner.png"));
        ImageIO.write(icon(), "png", new File(res, "mipmap-xhdpi/ic_launcher.png"));
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

    /** Largest size up to {@code max} at which every line fits into {@code width}. */
    static Font fitted(Graphics2D g, String[] names, float max, int width, String... lines) {
        for (float size = max; ; size -= 1f) {
            Font f = face(names, size);
            FontMetrics fm = g.getFontMetrics(f);
            boolean fits = true;
            for (String line : lines) {
                fits &= fm.stringWidth(line) <= width;
            }
            if (fits || size <= 10f) {
                return f;
            }
        }
    }

    static Graphics2D smooth(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g;
    }

    /** Shield with a check mark, centred in a box of the given size at (x, y). */
    static void mark(Graphics2D g, double x, double y, double size) {
        double s = size / 24.0;
        Path2D shield = new Path2D.Double();
        shield.moveTo(x + 12 * s, y + 2 * s);
        shield.lineTo(x + 4 * s, y + 5 * s);
        shield.lineTo(x + 4 * s, y + 11 * s);
        shield.curveTo(x + 4 * s, y + 16 * s, x + 7.4 * s, y + 20.8 * s, x + 12 * s, y + 22 * s);
        shield.curveTo(x + 16.6 * s, y + 20.8 * s, x + 20 * s, y + 16 * s, x + 20 * s, y + 11 * s);
        shield.lineTo(x + 20 * s, y + 5 * s);
        shield.closePath();
        g.setColor(SURFACE);
        g.fill(shield);
        g.setColor(ACCENT);
        g.setStroke(new BasicStroke((float) (1.6 * s), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(shield);
        Path2D check = new Path2D.Double();
        check.moveTo(x + 8.2 * s, y + 12.2 * s);
        check.lineTo(x + 10.9 * s, y + 14.9 * s);
        check.lineTo(x + 16 * s, y + 9.6 * s);
        g.setStroke(new BasicStroke((float) (2.0 * s), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(check);
    }

    static BufferedImage banner() {
        BufferedImage img = new BufferedImage(320, 180, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = smooth(img);
        g.setColor(BG);
        g.fillRect(0, 0, 320, 180);
        mark(g, 22, 54, 72);
        g.setColor(FG);
        g.setFont(fitted(g, BOLD, 30f, 320 - 106 - 12, "OpenShelf", "Keeper"));
        FontMetrics fm = g.getFontMetrics();
        g.drawString("OpenShelf", 106, 86);
        g.drawString("Keeper", 106, 86 + fm.getHeight() - 4);
        g.setColor(MUTED);
        g.setFont(fitted(g, REGULAR, 14f, 320 - 107 - 12, "Keeps bloat switched off"));
        g.drawString("Keeps bloat switched off", 107, 146);
        g.dispose();
        return img;
    }

    static BufferedImage icon() {
        BufferedImage img = new BufferedImage(96, 96, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = smooth(img);
        g.setColor(BG);
        g.fill(new RoundRectangle2D.Double(4, 4, 88, 88, 20, 20));
        mark(g, 14, 14, 68);
        g.dispose();
        return img;
    }
}
