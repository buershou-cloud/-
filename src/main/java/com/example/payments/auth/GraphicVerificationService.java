package com.example.payments.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Service
public class GraphicVerificationService {

    private static final String SESSION_ATTRIBUTE = GraphicVerificationService.class.getName() + ".challenge";
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final int WIDTH = 480;
    private static final int HEIGHT = 240;
    private static final int PIECE_SIZE = 64;
    private static final int MIN_X = 16;
    private static final int MAX_X = WIDTH - PIECE_SIZE - 16;
    private static final int TOLERANCE = 12;

    private final SecureRandom secureRandom = new SecureRandom();

    public GraphicChallengeView createChallenge(HttpServletRequest request) {
        int targetX = 112 + secureRandom.nextInt(220);
        int targetY = 82 + secureRandom.nextInt(54);
        Challenge challenge = new Challenge(UUID.randomUUID().toString(), targetX, targetY, Instant.now().plus(TTL));
        request.getSession(true).setAttribute(SESSION_ATTRIBUTE, challenge);
        PuzzleImages images = renderImages(targetX, targetY);
        return new GraphicChallengeView(
                challenge.id(),
                images.backgroundImage(),
                WIDTH,
                HEIGHT,
                MIN_X,
                MAX_X,
                images.pieceImage(),
                targetY,
                PIECE_SIZE
        );
    }

    public boolean verify(HttpServletRequest request, String challengeId, Integer verificationX) {
        HttpSession session = request.getSession(false);
        if (session == null || challengeId == null || verificationX == null) {
            return false;
        }
        Object value = session.getAttribute(SESSION_ATTRIBUTE);
        session.removeAttribute(SESSION_ATTRIBUTE);
        if (!(value instanceof Challenge challenge)) {
            return false;
        }
        if (!challenge.id().equals(challengeId) || Instant.now().isAfter(challenge.expiresAt())) {
            return false;
        }
        return Math.abs(challenge.targetX() - verificationX) <= TOLERANCE;
    }

    private PuzzleImages renderImages(int targetX, int targetY) {
        BufferedImage original = renderBackground();
        BufferedImage background = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D bg = background.createGraphics();
        prepare(bg);
        bg.drawImage(original, 0, 0, null);

        Shape targetShape = puzzleShape(targetX, targetY);
        bg.setComposite(AlphaComposite.SrcOver.derive(0.55f));
        bg.setColor(Color.WHITE);
        bg.fill(targetShape);
        bg.setComposite(AlphaComposite.SrcOver.derive(0.42f));
        bg.setColor(new Color(30, 41, 59));
        bg.fill(targetShape);
        bg.setComposite(AlphaComposite.SrcOver);
        bg.setStroke(new BasicStroke(2.4f));
        bg.setColor(new Color(255, 255, 255, 210));
        bg.draw(targetShape);
        bg.dispose();

        BufferedImage piece = new BufferedImage(PIECE_SIZE, PIECE_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D pg = piece.createGraphics();
        prepare(pg);
        Shape pieceShape = puzzleShape(0, 0);
        pg.setClip(pieceShape);
        pg.drawImage(original, -targetX, -targetY, null);
        pg.setClip(null);
        pg.setComposite(AlphaComposite.SrcOver.derive(0.16f));
        pg.setColor(Color.WHITE);
        pg.fill(pieceShape);
        pg.setComposite(AlphaComposite.SrcOver);
        pg.setStroke(new BasicStroke(2.2f));
        pg.setColor(new Color(255, 255, 255, 225));
        pg.draw(pieceShape);
        pg.dispose();

        return new PuzzleImages(toPngDataUrl(background), toPngDataUrl(piece));
    }

    private BufferedImage renderBackground() {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        prepare(g);

        g.setPaint(new GradientPaint(0, 0, new Color(178, 214, 241), 0, HEIGHT, new Color(232, 243, 249)));
        g.fillRect(0, 0, WIDTH, HEIGHT);

        drawCloud(g, 62, 34, 58);
        drawCloud(g, 342, 28, 48);

        GeneralPath farMountain = new GeneralPath();
        farMountain.moveTo(0, 112);
        farMountain.curveTo(70, 42, 112, 76, 168, 30);
        farMountain.curveTo(238, 98, 274, 24, 348, 82);
        farMountain.curveTo(406, 128, 442, 66, 480, 102);
        farMountain.lineTo(480, 166);
        farMountain.lineTo(0, 166);
        farMountain.closePath();
        g.setColor(new Color(197, 211, 219));
        g.fill(farMountain);

        GeneralPath nearMountain = new GeneralPath();
        nearMountain.moveTo(0, 142);
        nearMountain.curveTo(88, 82, 126, 132, 188, 84);
        nearMountain.curveTo(260, 162, 318, 58, 400, 116);
        nearMountain.curveTo(440, 142, 462, 128, 480, 136);
        nearMountain.lineTo(480, 184);
        nearMountain.lineTo(0, 184);
        nearMountain.closePath();
        g.setColor(new Color(65, 119, 148));
        g.fill(nearMountain);

        GeneralPath hillBack = new GeneralPath();
        hillBack.moveTo(0, 164);
        hillBack.curveTo(76, 130, 142, 190, 216, 148);
        hillBack.curveTo(304, 104, 362, 156, 480, 126);
        hillBack.lineTo(480, HEIGHT);
        hillBack.lineTo(0, HEIGHT);
        hillBack.closePath();
        g.setColor(new Color(133, 178, 87));
        g.fill(hillBack);

        GeneralPath hillFront = new GeneralPath();
        hillFront.moveTo(0, 198);
        hillFront.curveTo(86, 158, 148, 226, 226, 182);
        hillFront.curveTo(320, 130, 382, 198, 480, 164);
        hillFront.lineTo(480, HEIGHT);
        hillFront.lineTo(0, HEIGHT);
        hillFront.closePath();
        g.setColor(new Color(107, 160, 68));
        g.fill(hillFront);

        drawTree(g, 54, 157, 25);
        drawTree(g, 406, 126, 42);
        drawTree(g, 432, 145, 30);

        g.setColor(new Color(255, 255, 255, 72));
        g.fillOval(26, 110, 28, 28);
        g.fillOval(206, 116, 34, 34);

        g.dispose();
        return image;
    }

    private void drawCloud(Graphics2D g, int x, int y, int width) {
        g.setColor(new Color(255, 255, 255, 155));
        g.fillOval(x, y + width / 5, width / 2, width / 4);
        g.fillOval(x + width / 4, y, width / 2, width / 3);
        g.fillOval(x + width / 2, y + width / 7, width / 2, width / 4);
    }

    private void drawTree(Graphics2D g, int x, int y, int height) {
        g.setColor(new Color(91, 80, 54));
        g.fillRect(x + height / 6, y + height / 2, Math.max(3, height / 9), height / 2);
        g.setColor(new Color(35, 104, 54));
        Path2D canopy = new Path2D.Double();
        canopy.moveTo(x + height / 5.0, y);
        canopy.lineTo(x, y + height * 0.72);
        canopy.lineTo(x + height * 0.58, y + height * 0.72);
        canopy.closePath();
        g.fill(canopy);
    }

    private Shape puzzleShape(double x, double y) {
        double inset = PIECE_SIZE * 0.125;
        double base = PIECE_SIZE * 0.75;
        double knob = PIECE_SIZE * 0.28;
        Area area = new Area(new RoundRectangle2D.Double(x + inset, y + inset, base, base, 4, 4));
        area.add(new Area(new Ellipse2D.Double(x + PIECE_SIZE * 0.36, y + PIECE_SIZE * 0.01, knob, knob)));
        area.subtract(new Area(new Ellipse2D.Double(x + PIECE_SIZE * 0.72, y + PIECE_SIZE * 0.36, knob, knob)));
        area.add(new Area(new Ellipse2D.Double(x + PIECE_SIZE * 0.36, y + PIECE_SIZE * 0.72, knob, knob)));
        return area;
    }

    private void prepare(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    }

    private String toPngDataUrl(BufferedImage image) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray());
        } catch (IOException ex) {
            throw new IllegalStateException("生成图形验证失败", ex);
        }
    }

    private record Challenge(String id, int targetX, int targetY, Instant expiresAt) {
    }

    private record PuzzleImages(String backgroundImage, String pieceImage) {
    }
}
