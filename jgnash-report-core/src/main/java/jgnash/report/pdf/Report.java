/*
 * jGnash, a personal finance application
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
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package jgnash.report.pdf;

import jgnash.report.table.AbstractReportTableModel;
import jgnash.util.NotNull;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;

import java.awt.Color;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;

import static jgnash.util.LogUtil.logSevere;

/**
 * Base report format definition.
 * <p>
 * Units of measure is in Points
 *
 * @author Craig Cavanaugh
 * <p>
 * TODO: Footer, full margin control
 */
@SuppressWarnings("WeakerAccess")
public class Report {

    private PDRectangle pageSize;

    private float tableFontSize;

    private float footerFontSize;

    private PDFont tableFont;

    private PDFont headerFont;

    private PDFont footerFont;

    private float margin;

    private float cellPadding = 2;

    final Color headerBackground = Color.DARK_GRAY;

    final Color headerTextColor = Color.WHITE;

    public Report() {
        setPageSize(PDRectangle.LETTER, false);
        setTableFont(PDType1Font.HELVETICA);
        setFooterFont(PDType1Font.HELVETICA_OBLIQUE);

        setTableFontSize(12);
        setFooterFontSize(9);
    }

    @NotNull
    public PDRectangle getPageSize() {
        return pageSize;
    }

    public void setPageSize(@NotNull PDRectangle pageSize, boolean landscape) {

        this.pageSize = pageSize;

        if (landscape) {
            this.pageSize = new PDRectangle(pageSize.getHeight(), pageSize.getWidth());
        }
    }

    @NotNull
    public PDFont getTableFont() {
        return tableFont;
    }

    public void setTableFont(@NotNull PDFont tableFont) {
        this.tableFont = tableFont;
    }

    public float getTableFontSize() {
        return tableFontSize;
    }

    public void setTableFontSize(float tableFontSize) {
        this.tableFontSize = tableFontSize;
    }

    private float getTableRowHeight() {
        return getTableFontSize() + 2 * getCellPadding();
    }

    public boolean isLandscape() {
        return pageSize.getWidth() > pageSize.getHeight();
    }

    public PDFont getHeaderFont() {
        return headerFont;
    }

    public void setHeaderFont(PDFont headerFont) {
        this.headerFont = headerFont;
    }

    public float getCellPadding() {
        return cellPadding;
    }

    public void setCellPadding(float cellPadding) {
        this.cellPadding = cellPadding;
    }

    public float getMargin() {
        return margin;
    }

    public void setMargin(float margin) {
        this.margin = margin;
    }

    public void addTable(final PDDocument doc, final AbstractReportTableModel table) throws IOException {

        // TODO Allow for summation row for certain report types
        final int rowsPerPage = (int) Math.floor(getAvailableHeight() / getTableRowHeight()) - 1;
        final int numberOfPages = (int) Math.ceil((float) table.getRowCount() / (float) rowsPerPage);

        int startRow = 0;
        int remainingRowCount = table.getRowCount();
        int rows;

        for (int i = 0; i < numberOfPages; i++) {
            final PDPage page = createPage();
            doc.addPage(page);

            try (final PDPageContentStream contentStream = new PDPageContentStream(doc, page)) {
                rows = rowsPerPage;

                if (remainingRowCount < rows) {
                    rows = remainingRowCount;
                }

                addTableSection(table, contentStream, startRow, rows);

                remainingRowCount = remainingRowCount - rows;
                startRow += rowsPerPage;

            } catch (final IOException e) {
                logSevere(Report.class, e);
                throw (e);
            }
        }
    }

    private void addTableSection(final AbstractReportTableModel table, final PDPageContentStream contentStream,
                                 final int startRow, final int rows) throws IOException {

        float yTop = getPageSize().getHeight() - getMargin();

        float yPos = yTop;
        float xPos = getMargin() + getCellPadding();

        yPos = yPos - (getTableRowHeight() / 2)
                - ((getTableFont().getFontDescriptor().getFontBoundingBox().getHeight() / 1000 * getTableFontSize()) / 4);

        contentStream.setFont(getHeaderFont(), getTableFontSize());

        float columnWidth = getAvailableWidth() / table.getColumnCount();  // TODO, calculate/pack column widths

        // add the header
        contentStream.setNonStrokingColor(headerBackground);
        fillRect(contentStream, getMargin(), yTop - getTableRowHeight(), getAvailableWidth(), getTableRowHeight());

        contentStream.setNonStrokingColor(headerTextColor);

        for (int i = 0; i < table.getColumnCount(); i++) {
            drawText(contentStream, xPos, yPos, table.getColumnName(i));

            xPos += columnWidth;
        }

        // add the rows
        contentStream.setFont(getTableFont(), getTableFontSize());
        contentStream.setNonStrokingColor(Color.BLACK);

        for (int i = 0; i < rows; i++) {
            int row = i + startRow;

            xPos = getMargin() + getCellPadding();
            yPos -= getTableRowHeight();

            for (int j = 0; j < table.getColumnCount(); j++) {

                final Object value = table.getValueAt(row, j);

                if (value != null) {
                    drawText(contentStream, xPos, yPos, table.getValueAt(row, j).toString());
                }

                xPos += columnWidth;
            }
        }

        // add row lines
        yPos = yTop;
        xPos = getMargin();
        for (int i = 0; i <= rows + 1; i++) {
            drawLine(contentStream, xPos, yPos, getAvailableWidth() + getMargin(), yPos);
            yPos -= getTableRowHeight();
        }

        // add column lines
        yPos = yTop;
        xPos = getMargin();
        for (int i = 0; i < table.getColumnCount() + 1; i++) {
            drawLine(contentStream, xPos, yPos, xPos, yPos - getTableRowHeight() * (rows + 1));
            xPos += columnWidth;
        }

        contentStream.close();
    }

    private void drawText(final PDPageContentStream contentStream, final float xStart, final float yStart,
                          final String text) throws IOException {
        contentStream.beginText();
        contentStream.newLineAtOffset(xStart, yStart);
        contentStream.showText(text);
        contentStream.endText();
    }


    private void drawLine(final PDPageContentStream contentStream, final float xStart, final float yStart,
                          final float xEnd, final float yEnd) throws IOException {
        contentStream.moveTo(xStart, yStart);
        contentStream.lineTo(xEnd, yEnd);
        contentStream.stroke();
    }

    private void fillRect(final PDPageContentStream contentStream, final float x, final float y, final float width,
                          final float height) throws IOException {
        contentStream.addRect(x, y, width, height);
        contentStream.fill();
    }

    private float getAvailableHeight() {
        return getPageSize().getHeight() - getMargin() * 2;
    }

    private float getAvailableWidth() {
        return getPageSize().getWidth() - getMargin() * 2;
    }

    public void addFooter(final PDDocument doc) throws IOException {

        final String timeStamp = "Created " + DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).format(LocalDateTime.now());

        final int pageCount = doc.getNumberOfPages();
        float yStart = getMargin() * 2/3;

        for (int i = 0; i < pageCount; i++) {
            PDPage page = doc.getPage(i);

            final String pageText = String.format("Page %s of %s", i + 1, pageCount);
            final float width = getStringWidth(getFooterFont(), pageText, getFooterFontSize());

            try (final PDPageContentStream contentStream = new PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true)) {
                contentStream.setFont(getFooterFont(), getFooterFontSize());

                drawText(contentStream, getMargin(), yStart, timeStamp);
                drawText(contentStream, getPageSize().getWidth() - getMargin() - width, yStart, pageText);
            } catch (final IOException e) {
                logSevere(Report.class, e);
            }
        }
    }

    private PDPage createPage() {
        PDPage page = new PDPage();
        page.setMediaBox(getPageSize());
        return page;
    }

    private static float getStringWidth(final PDFont font, final String text, final float fontSize) throws IOException {
        return font.getStringWidth(text) / 1000 * fontSize;
    }

    public float getFooterFontSize() {
        return footerFontSize;
    }

    public void setFooterFontSize(float footerFontSize) {
        this.footerFontSize = footerFontSize;
    }

    public PDFont getFooterFont() {
        return footerFont;
    }

    public void setFooterFont(PDFont footerFont) {
        this.footerFont = footerFont;
    }
}
