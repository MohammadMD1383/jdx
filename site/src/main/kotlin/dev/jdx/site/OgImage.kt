package dev.jdx.site

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Social preview cards (Open Graph / X / Slack / Discord unfurls), one per page, drawn with
 * Java2D at build time so the title and version on the card always match the page.
 */
object OgImage {
    const val WIDTH = 1200
    const val HEIGHT = 630

    private val background = Color(0x0c, 0x0e, 0x13)
    private val surface = Color(0x15, 0x19, 0x22)
    private val accent = Color(0x4a, 0xde, 0x80)
    private val ink = Color(0xf2, 0xf0, 0xea)
    private val muted = Color(0x9a, 0xa3, 0xb5)
    private val teal = Color(0x86, 0xef, 0xac)

    fun render(title: String, subtitle: String, footer: String, badge: String?): ByteArray {
        System.setProperty("java.awt.headless", "true")
        val image = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.color = background
            g.fillRect(0, 0, WIDTH, HEIGHT)
            // Flat colours only: a gradient would triple the PNG size of every card.
            g.color = accent
            g.fillRect(0, 0, WIDTH, 8)

            // Brand mark: rounded square with a prompt chevron.
            g.color = accent
            g.fill(RoundRectangle2D.Double(72.0, 64.0, 72.0, 72.0, 18.0, 18.0))
            g.color = background
            g.stroke = BasicStroke(7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.drawPolyline(intArrayOf(92, 106, 92), intArrayOf(86, 100, 114), 3)
            g.drawLine(113, 117, 128, 117)
            g.color = ink
            g.font = Font(Font.MONOSPACED, Font.BOLD, 46)
            g.drawString("jdx", 164, 116)

            g.color = ink
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 64)
            var y = 250
            for (line in wrap(title, g.font, 1050, g).take(3)) {
                g.drawString(line, 72, y)
                y += 78
            }
            g.color = muted
            g.font = Font(Font.SANS_SERIF, Font.PLAIN, 30)
            for (line in wrap(subtitle, g.font, 1050, g).take(2)) {
                g.drawString(line, 72, y + 6)
                y += 42
            }

            g.color = surface
            g.fill(RoundRectangle2D.Double(72.0, 520.0, 1056.0, 58.0, 14.0, 14.0))
            g.font = Font(Font.MONOSPACED, Font.PLAIN, 26)
            g.color = teal
            g.drawString(footer, 96, 558)
            if (badge != null) {
                g.font = Font(Font.MONOSPACED, Font.BOLD, 28)
                val width = g.fontMetrics.stringWidth(badge)
                g.color = surface
                g.fill(RoundRectangle2D.Double(WIDTH - 72.0 - width - 36, 72.0, width + 36.0, 54.0, 27.0, 27.0))
                g.color = accent
                g.drawString(badge, WIDTH - 72 - width - 18, 109)
            }
        } finally {
            g.dispose()
        }
        val bytes = ByteArrayOutputStream()
        ImageIO.write(image, "png", bytes)
        return bytes.toByteArray()
    }

    /** The brand mark as a square PNG (apple-touch-icon: iOS home screen, some share sheets). */
    fun icon(size: Int): ByteArray {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val unit = size / 32.0
            g.color = Color(0x16, 0xa3, 0x4a)
            g.fillRect(0, 0, size, size)
            g.color = Color.WHITE
            g.stroke = BasicStroke((2.6 * unit).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            fun x(v: Double) = (v * unit).toInt()
            g.drawPolyline(intArrayOf(x(9.0), x(14.0), x(9.0)), intArrayOf(x(11.0), x(16.0), x(21.0)), 3)
            g.drawLine(x(16.0), x(22.0), x(23.0), x(22.0))
        } finally {
            g.dispose()
        }
        val bytes = ByteArrayOutputStream()
        ImageIO.write(image, "png", bytes)
        return bytes.toByteArray()
    }

    private fun wrap(text: String, font: Font, maxWidth: Int, g: java.awt.Graphics2D): List<String> {
        val metrics = g.getFontMetrics(font)
        val lines = mutableListOf<String>()
        var current = ""
        for (word in text.split(' ')) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (metrics.stringWidth(candidate) > maxWidth && current.isNotEmpty()) {
                lines += current
                current = word
            } else {
                current = candidate
            }
        }
        if (current.isNotEmpty()) lines += current
        return lines
    }
}
