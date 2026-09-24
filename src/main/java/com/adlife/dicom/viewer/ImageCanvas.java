package com.adlife.dicom.viewer;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.adlife.dicom.imaging.PixelRenderer;

/**
 * Image panel: left-drag = window/level, wheel = scroll images/frames,
 * right-drag = pan, measure tool with pixel-spacing calibration.
 */
public class ImageCanvas extends JComponent {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(ImageCanvas.class);

    private List<Attributes> series = new ArrayList<Attributes>();
    private int imageIndex = 0;
    private int frameIndex = 0;

    private BufferedImage rendered;
    private double zoom = -1; // -1 = fit
    private int panX = 0, panY = 0;
    private Double wlCenter, wlWidth; // user overrides

    private boolean measureMode;
    private final List<double[]> lines = new ArrayList<double[]>(); // x1,y1,x2,y2 in image px
    private double[] draftLine;

    public ImageCanvas() {
        setBackground(Color.BLACK);
        MouseAdapter mouse = new MouseAdapter() {
            private int lastX, lastY;
            @Override public void mousePressed(MouseEvent e) {
                lastX = e.getX(); lastY = e.getY();
                if (measureMode && rendered != null) {
                    double[] p = toImage(e.getX(), e.getY());
                    draftLine = new double[] { p[0], p[1], p[0], p[1] };
                }
            }
            @Override public void mouseDragged(MouseEvent e) {
                if (measureMode && draftLine != null) {
                    double[] p = toImage(e.getX(), e.getY());
                    draftLine[2] = p[0]; draftLine[3] = p[1];
                    repaint();
                } else if (javax.swing.SwingUtilities.isRightMouseButton(e)) {
                    panX += e.getX() - lastX; panY += e.getY() - lastY;
                    lastX = e.getX(); lastY = e.getY();
                    repaint();
                } else {
                    adjustWindow(e.getX() - lastX, e.getY() - lastY);
                    lastX = e.getX(); lastY = e.getY();
                }
            }
            @Override public void mouseReleased(MouseEvent e) {
                if (measureMode && draftLine != null) {
                    lines.add(draftLine);
                    draftLine = null;
                    repaint();
                }
            }
            @Override public void mouseWheelMoved(MouseWheelEvent e) {
                step(e.getWheelRotation());
            }
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) { zoom = -1; panX = panY = 0; repaint(); }
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
    }

    public void setSeries(List<Attributes> frames) {
        this.series = new ArrayList<Attributes>(frames);
        this.imageIndex = 0;
        this.frameIndex = 0;
        this.wlCenter = null;
        this.wlWidth = null;
        this.zoom = -1;
        this.panX = this.panY = 0;
        this.lines.clear();
        render();
    }

    public void toggleMeasure() {
        measureMode = !measureMode;
        if (!measureMode) { lines.clear(); draftLine = null; }
        repaint();
    }

    private Attributes current() {
        if (series.isEmpty()) return null;
        return series.get(imageIndex);
    }

    private int frameCount(Attributes ds) {
        return ds != null ? ds.getInt(Tag.NumberOfFrames, 1) : 1;
    }

    private void step(int delta) {
        Attributes ds = current();
        if (ds == null) return;
        int frames = frameCount(ds);
        int nextFrame = frameIndex + delta;
        if (nextFrame >= 0 && nextFrame < frames) {
            frameIndex = nextFrame;
        } else {
            int nextImage = imageIndex + delta;
            if (nextImage < 0 || nextImage >= series.size()) return;
            imageIndex = nextImage;
            Attributes nds = current();
            frameIndex = delta > 0 ? 0 : Math.max(0, frameCount(nds) - 1);
        }
        render();
    }

    private void adjustWindow(int dx, int dy) {
        Attributes ds = current();
        if (ds == null) return;
        double c = wlCenter != null ? wlCenter : ds.getDouble(Tag.WindowCenter, 0);
        double w = wlWidth != null ? wlWidth : ds.getDouble(Tag.WindowWidth, 400);
        wlCenter = c + dy * 2.0;
        wlWidth = Math.max(1.0, w + dx * 4.0);
        render();
    }

    /** Slice frame f out of a (possibly multiframe) dataset for the renderer. */
    private static Attributes frameDataset(Attributes ds, int f) {
        int frames = ds.getInt(Tag.NumberOfFrames, 1);
        if (frames <= 1) return ds;
        Object pd = ds.getValue(Tag.PixelData);
        if (!(pd instanceof byte[])) return ds;
        byte[] all = (byte[]) pd;
        int rows = ds.getInt(Tag.Rows, 0), cols = ds.getInt(Tag.Columns, 0);
        int samples = ds.getInt(Tag.SamplesPerPixel, 1);
        int bytesPer = ds.getInt(Tag.BitsAllocated, 8) / 8;
        int frameLen = rows * cols * samples * bytesPer;
        if (frameLen <= 0 || all.length < frameLen * frames) return ds;
        Attributes one = new Attributes(ds);
        byte[] slice = new byte[frameLen];
        System.arraycopy(all, f * frameLen, slice, 0, frameLen);
        one.setBytes(Tag.PixelData, VR.OB, slice);
        return one;
    }

    private void render() {
        Attributes ds = current();
        if (ds == null) { rendered = null; repaint(); return; }
        Attributes frame = frameDataset(ds, frameIndex);
        Attributes overrides = new Attributes();
        if (wlCenter != null) overrides.setDouble(Tag.WindowCenter, VR.DS, wlCenter);
        if (wlWidth != null) overrides.setDouble(Tag.WindowWidth, VR.DS, wlWidth);
        try {
            rendered = PixelRenderer.renderPixels(frame, overrides,
                    ds.getString(Tag.PhotometricInterpretation), null, null);
        } catch (Exception e) {
            LOG.warn("Render failed: {}", e.toString());
            rendered = null;
        }
        repaint();
    }

    private double effectiveZoom() {
        if (rendered == null) return 1;
        if (zoom > 0) return zoom;
        double zx = (double) getWidth() / rendered.getWidth();
        double zy = (double) getHeight() / rendered.getHeight();
        return Math.min(1.0, Math.min(zx, zy));
    }

    private double[] toImage(int sx, int sy) {
        double z = effectiveZoom();
        int ox = (int) ((getWidth() - rendered.getWidth() * z) / 2) + panX;
        int oy = (int) ((getHeight() - rendered.getHeight() * z) / 2) + panY;
        return new double[] { (sx - ox) / z, (sy - oy) / z };
    }

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0;
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, getWidth(), getHeight());
        if (rendered == null) {
            g.setColor(Color.GRAY);
            g.drawString("Open DICOM files, a folder, or the archive to begin.", 20, 30);
            return;
        }
        double z = effectiveZoom();
        int ox = (int) ((getWidth() - rendered.getWidth() * z) / 2) + panX;
        int oy = (int) ((getHeight() - rendered.getHeight() * z) / 2) + panY;
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(rendered, ox, oy,
                (int) (rendered.getWidth() * z), (int) (rendered.getHeight() * z), null);

        Attributes ds = current();
        g.setColor(Color.YELLOW);
        String info = (imageIndex + 1) + "/" + series.size();
        int frames = frameCount(ds);
        if (frames > 1) info += "  frame " + (frameIndex + 1) + "/" + frames;
        g.drawString(info, 10, getHeight() - 10);

        double pxSpacing = pixelSpacing(ds);
        g.setColor(Color.GREEN);
        drawLines(g, z, ox, oy, pxSpacing);
    }

    private double pixelSpacing(Attributes ds) {
        if (ds == null) return 0;
        String s = ds.getString(Tag.PixelSpacing);
        if (s == null) s = ds.getString(Tag.ImagerPixelSpacing);
        if (s == null) return 0;
        try {
            return Double.parseDouble(s.split("\\\\")[0]);
        } catch (Exception e) {
            return 0;
        }
    }

    private void drawLines(Graphics2D g, double z, int ox, int oy, double pxSpacing) {
        List<double[]> all = new ArrayList<double[]>(lines);
        if (draftLine != null) all.add(draftLine);
        for (double[] l : all) {
            int x1 = (int) (ox + l[0] * z), y1 = (int) (oy + l[1] * z);
            int x2 = (int) (ox + l[2] * z), y2 = (int) (oy + l[3] * z);
            g.drawLine(x1, y1, x2, y2);
            double pxLen = Math.hypot(l[2] - l[0], l[3] - l[1]);
            String label = pxSpacing > 0
                    ? String.format("%.1f mm", pxLen * pxSpacing)
                    : String.format("%.0f px", pxLen);
            g.drawString(label, (x1 + x2) / 2 + 6, (y1 + y2) / 2 - 6);
        }
    }

    public void exportPng(Component parent) {
        if (rendered == null) return;
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("adlife-image.png"));
        if (fc.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) return;
        try {
            ImageIO.write(rendered, "PNG", fc.getSelectedFile());
        } catch (Exception e) {
            JOptionPane.showMessageDialog(parent, "Export failed: " + e.getMessage());
        }
    }

    public void printCurrent() {
        if (rendered == null) return;
        java.awt.print.PrinterJob job = java.awt.print.PrinterJob.getPrinterJob();
        job.setPrintable(new java.awt.print.Printable() {
            @Override
            public int print(java.awt.Graphics graphics, java.awt.print.PageFormat pf, int page) {
                if (page > 0) return NO_SUCH_PAGE;
                Graphics2D g = (Graphics2D) graphics;
                g.translate(pf.getImageableX(), pf.getImageableY());
                double scale = Math.min(pf.getImageableWidth() / rendered.getWidth(),
                        pf.getImageableHeight() / rendered.getHeight());
                g.drawImage(rendered, 0, 0,
                        (int) (rendered.getWidth() * scale),
                        (int) (rendered.getHeight() * scale), null);
                return PAGE_EXISTS;
            }
        });
        if (job.printDialog()) {
            try {
                job.print();
            } catch (Exception e) {
                JOptionPane.showMessageDialog(this, "Print failed: " + e.getMessage());
            }
        }
    }
}
