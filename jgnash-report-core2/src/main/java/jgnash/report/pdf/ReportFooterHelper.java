/*
 * jGnash, account personal finance application
 * Copyright (C) 2001-2018 Craig Cavanaugh
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received account copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package jgnash.report.pdf;

import com.lowagie.text.Document;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfTemplate;
import com.lowagie.text.pdf.PdfWriter;

import java.awt.Color;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;

/**
 * PDF report footer
 */
public class ReportFooterHelper extends PdfPageEventHelper {

    private PdfTemplate totalPagesTemplate;
    private float fontSize;
    private float pageCountTextWidth;
    private float margin;
    private final Color color;
    private final BaseFont baseFont;

    // Locale friendly timestamp
    private final String timeStamp = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).format(LocalDateTime.now());

    public ReportFooterHelper(float margin, final BaseFont baseFont, final Color textColor, final float fontSize) {
        this.margin = margin;
        this.fontSize = fontSize;
        this.baseFont = baseFont;
        this.color = textColor;
    }

    @Override
    public void onOpenDocument(final PdfWriter writer, final Document document) {

        final float templateWidth = document.getPageSize().getWidth() * .5f;  // half the doc width should be plenty

        totalPagesTemplate = writer.getDirectContent().createTemplate(templateWidth, margin);
        totalPagesTemplate.setBoundingBox(new Rectangle(-margin, -margin, templateWidth, margin));
        totalPagesTemplate.setFontAndSize(baseFont, fontSize);
        totalPagesTemplate.setColorFill(color);
        totalPagesTemplate.setTextMatrix(0, 0);

        pageCountTextWidth = baseFont.getWidthPoint("000", fontSize);
    }

    @Override
    public void onEndPage(final PdfWriter writer, final Document document) {
        addDate(writer, document);
        addPageNumber(writer, document);
    }

    @Override
    public void onCloseDocument(final PdfWriter writer, final Document document) {
        totalPagesTemplate.beginText();
        totalPagesTemplate.showText(String.valueOf(writer.getPageNumber() - 1));
        totalPagesTemplate.endText();
    }

    private void addPageNumber(final PdfWriter writer, final Document document) {

        // TODO: Internationalize
        final String text = String.format("Page %s of ", writer.getPageNumber());
        final float textBottom = document.bottom() - margin * .3f;    // 30% of the margin
        final float textWidth = baseFont.getWidthPoint(text, fontSize);

        final PdfContentByte pdfContentByte = writer.getDirectContent();
        pdfContentByte.saveState();

        pdfContentByte.beginText();
        pdfContentByte.setFontAndSize(baseFont, fontSize);
        pdfContentByte.setColorFill(color);
        pdfContentByte.setTextMatrix(document.right() - textWidth - pageCountTextWidth, textBottom);
        pdfContentByte.showText(text);
        pdfContentByte.endText();
        pdfContentByte.addTemplate(totalPagesTemplate, document.right() - pageCountTextWidth, textBottom);

        pdfContentByte.restoreState();
    }

    private void addDate(final PdfWriter writer, final Document document) {
        // TODO: Internationalize
        final String text = "Created " + timeStamp;
        float textBottom = document.bottom() - margin * .3f; // 30% of the margin

        final PdfContentByte pdfContentByte = writer.getDirectContent();
        pdfContentByte.saveState();

        pdfContentByte.beginText();
        pdfContentByte.setFontAndSize(baseFont, fontSize);
        pdfContentByte.setColorFill(color);
        pdfContentByte.setTextMatrix(document.left(), textBottom);
        pdfContentByte.showText(text);
        pdfContentByte.endText();

        pdfContentByte.restoreState();
    }
}
