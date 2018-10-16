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
import jgnash.report.table.ColumnStyle;
import jgnash.text.CommodityFormat;
import jgnash.time.DateUtils;
import jgnash.util.NotNull;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;

import java.awt.Color;

import java.io.IOException;
import java.text.NumberFormat;
import java.time.LocalDate;
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

    private String ellipsis = "...";

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

    public void setHeaderFont(final PDFont headerFont) {
        this.headerFont = headerFont;
    }

    public float getCellPadding() {
        return cellPadding;
    }

    public void setCellPadding(final float cellPadding) {
        this.cellPadding = cellPadding;
    }

    public float getMargin() {
        return margin;
    }

    public void setMargin(float margin) {
        this.margin = margin;
    }

    public float getFooterFontSize() {
        return footerFontSize;
    }

    public void setFooterFontSize(final float footerFontSize) {
        this.footerFontSize = footerFontSize;
    }

    public PDFont getFooterFont() {
        return footerFont;
    }

    public void setFooterFont(final PDFont footerFont) {
        this.footerFont = footerFont;
    }

    public void addTable(final PDDocument doc, final AbstractReportTableModel table, float yStartMargin) throws IOException {

        float[] columnWidths = getColumnWidths(table);

        final int firstPageRows = (int) Math.floor((getAvailableHeight() - yStartMargin) / getTableRowHeight()) - 1;

        // TODO Allow for summation row for certain report types
        final int rowsPerPage = (int) Math.floor(getAvailableHeight() / getTableRowHeight()) - 1;
        final int numberOfRemainingPages = (int) Math.ceil((float) (table.getRowCount() - firstPageRows) / (float) rowsPerPage);

        int startRow = 0;
        int remainingRowCount = table.getRowCount();
        int rows;

        // first page
        PDPage page = createPage();
        doc.addPage(page);
        try (final PDPageContentStream contentStream = new PDPageContentStream(doc, page)) {
            rows = firstPageRows;

            if (remainingRowCount < rows) {
                rows = remainingRowCount;
            }

            addTableSection(table, contentStream, startRow, rows, columnWidths, yStartMargin);

            remainingRowCount = remainingRowCount - rows;
            startRow += rows;

        } catch (final IOException e) {
            logSevere(Report.class, e);
            throw (e);
        }


        for (int i = 0; i < numberOfRemainingPages; i++) {
            page = createPage();
            doc.addPage(page);

            try (final PDPageContentStream contentStream = new PDPageContentStream(doc, page)) {
                rows = rowsPerPage;

                if (remainingRowCount < rows) {
                    rows = remainingRowCount;
                }

                addTableSection(table, contentStream, startRow, rows, columnWidths, 0);

                remainingRowCount = remainingRowCount - rows;
                startRow += rows;

            } catch (final IOException e) {
                logSevere(Report.class, e);
                throw (e);
            }
        }
    }

    private void addTableSection(final AbstractReportTableModel table, final PDPageContentStream contentStream,
                                 final int startRow, final int rows, float[] columnWidths, float yStartMargin) throws IOException {

        float yTop = getPageSize().getHeight() - getMargin() - yStartMargin;

        float yPos = yTop;
        float xPos = getMargin() + getCellPadding();

        yPos = yPos - (getTableRowHeight() / 2)
                - ((getTableFont().getFontDescriptor().getFontBoundingBox().getHeight() / 1000 * getTableFontSize()) / 4);

        contentStream.setFont(getHeaderFont(), getTableFontSize());

        // add the header
        contentStream.setNonStrokingColor(headerBackground);
        fillRect(contentStream, getMargin(), yTop - getTableRowHeight(), getAvailableWidth(), getTableRowHeight());

        contentStream.setNonStrokingColor(headerTextColor);

        for (int col = 0; col < table.getColumnCount(); col++) {
            drawText(contentStream, xPos, yPos, table.getColumnName(col));
            xPos += columnWidths[col];
        }

        // add the rows
        contentStream.setFont(getTableFont(), getTableFontSize());
        contentStream.setNonStrokingColor(Color.BLACK);

        for (int i = 0; i < rows; i++) {
            int row = i + startRow;

            xPos = getMargin() + getCellPadding();
            yPos -= getTableRowHeight();

            for (int col = 0; col < table.getColumnCount(); col++) {

                final Object value = table.getValueAt(row, col);

                if (value != null) {
                    float shift = 0;
                    float availWidth = columnWidths[col] - getCellPadding() * 2;

                    final String text = truncateText(formatValue(table.getValueAt(row, col), col, table), availWidth,
                            getTableFont(), getTableFontSize());

                    if (rightAlign(col, table)) {
                        shift = availWidth - getStringWidth(text, getTableFont(), getTableFontSize());
                    }

                    drawText(contentStream, xPos + shift, yPos, text);
                }

                xPos += columnWidths[col];
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
        for (int col = 0; col < table.getColumnCount(); col++) {
            drawLine(contentStream, xPos, yPos, xPos, yPos - getTableRowHeight() * (rows + 1));
            xPos += columnWidths[col];
        }

        // end of last column
        drawLine(contentStream, xPos, yPos, xPos, yPos - getTableRowHeight() * (rows + 1));

        contentStream.close();
    }

    private String formatValue(final Object value, final int column, final AbstractReportTableModel tableModel) {
        if (value == null) {
            return " ";
        }

        final ColumnStyle columnStyle = tableModel.getColumnStyle(column);

        switch (columnStyle) {
            case TIMESTAMP:
                final DateTimeFormatter dateTimeFormatter = DateUtils.getShortDateTimeFormatter();
                return dateTimeFormatter.format((LocalDateTime) value);
            case SHORT_DATE:
                final DateTimeFormatter dateFormatter = DateUtils.getShortDateFormatter();
                return dateFormatter.format((LocalDate) value);
            case SHORT_AMOUNT:
                final NumberFormat shortNumberFormat = CommodityFormat.getShortNumberFormat(tableModel.getCurrency());
                return shortNumberFormat.format(value);
            case BALANCE:
            case BALANCE_WITH_SUM:
            case BALANCE_WITH_SUM_AND_GLOBAL:
            case AMOUNT_SUM:
                final NumberFormat numberFormat = CommodityFormat.getFullNumberFormat(tableModel.getCurrency());
                return numberFormat.format(value);
            default:
                return value.toString();
        }
    }

    private boolean rightAlign(final int column, final AbstractReportTableModel tableModel) {
        final ColumnStyle columnStyle = tableModel.getColumnStyle(column);

        switch (columnStyle) {
            case SHORT_AMOUNT:
            case BALANCE:
            case BALANCE_WITH_SUM:
            case BALANCE_WITH_SUM_AND_GLOBAL:
            case AMOUNT_SUM:
                return true;
            default:
                return false;
        }
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
        float yStart = getMargin() * 2 / 3;

        for (int i = 0; i < pageCount; i++) {
            PDPage page = doc.getPage(i);

            final String pageText = String.format("Page %s of %s", i + 1, pageCount);
            final float width = getStringWidth(pageText, getFooterFont(), getFooterFontSize());

            try (final PDPageContentStream contentStream = new PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true)) {
                contentStream.setFont(getFooterFont(), getFooterFontSize());

                drawText(contentStream, getMargin(), yStart, timeStamp);
                drawText(contentStream, getPageSize().getWidth() - getMargin() - width, yStart, pageText);
            } catch (final IOException e) {
                logSevere(Report.class, e);
            }
        }
    }

    private String truncateText(String text, float availWidth, final PDFont font, final float fontSize) throws IOException {
        if (text != null) {
            String content = text;

            float width = getStringWidth(content, font, fontSize);

            // munch down the end of the string until it fits
            if (width > availWidth) {
                while (getStringWidth(content + getEllipsis(), font, fontSize) > availWidth && !content.isEmpty()) {
                    content = content.substring(0, content.length() - 1);
                }

                content = content + getEllipsis();
            }

            return content;
        }

        return null;
    }

    private PDPage createPage() {
        PDPage page = new PDPage();
        page.setMediaBox(getPageSize());
        return page;
    }

    private static float getStringWidth(final String text, final PDFont font, final float fontSize) throws IOException {
        return font.getStringWidth(text) / 1000 * fontSize;
    }

    public String getEllipsis() {
        return ellipsis;
    }

    public void setEllipsis(String ellipsis) {
        this.ellipsis = ellipsis;
    }

    private float[] getColumnWidths(final AbstractReportTableModel table) throws IOException {
        float[] widths = new float[table.getColumnCount()]; // calculated optimal widths

        float measuredWidth = 0;
        float fixedWidth = 0;
        boolean compressAll = false;
        int flexColumns = 0;

        for (int i = 0; i < table.getColumnCount(); i++) {
            final String protoValue = table.getColumnPrototypeValueAt(i);

            float headerWidth = getStringWidth(table.getColumnName(i), getHeaderFont(), getTableFontSize()) + getCellPadding() * 2;
            float cellTextWidth = getStringWidth(protoValue, getTableFont(), getTableFontSize()) + getCellPadding() * 2;

            widths[i] = Math.max(headerWidth, cellTextWidth);

            measuredWidth += widths[i];

            if (table.isColumnFixedWidth(i)) {
                fixedWidth += widths[i];
            } else {
                flexColumns++;
            }
        }

        if (fixedWidth > getAvailableWidth()) {
            compressAll = true;
            flexColumns = table.getColumnCount();
        }

        float widthDelta;

        if (compressAll) {  // make it ugly
            widthDelta = (getAvailableWidth() - measuredWidth) / table.getColumnCount();
        } else {
            widthDelta = (getAvailableWidth() - measuredWidth) / flexColumns;
        }

        for (int col = 0; col < table.getColumnCount(); col++) {
            if (compressAll || !table.isColumnFixedWidth(col)) {
                widths[col] += widthDelta;
            }
        }

        return widths;
    }


}
