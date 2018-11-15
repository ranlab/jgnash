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
import jgnash.resource.util.ResourceUtils;
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
import java.text.MessageFormat;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.TreeSet;

import static jgnash.util.LogUtil.logSevere;

/**
 * Base report format definition.
 * <p>
 * Units of measure is in Points
 * <p>
 * The origin of a PDFBox page is the bottom left corner
 *
 * @author Craig Cavanaugh
 * <p>
 * TODO: full margin control, crosstabulation
 */
@SuppressWarnings("WeakerAccess")
public class Report {

    protected static final ResourceBundle rb = ResourceUtils.getBundle();

    private static final int DEFAULT_BASE_FONT_SIZE = 11;

    private String ellipsis = "...";

    private PDRectangle pageSize;

    private float baseFontSize;

    private PDFont tableFont;

    private PDFont headerFont;

    private PDFont footerFont;

    private float margin;

    private float cellPadding = 2;

    final Color headerBackground = Color.DARK_GRAY;

    final Color headerTextColor = Color.WHITE;

    final float FOOTER_SCALE = 0.80f;

    final float DEFAULT_LINE_WIDTH = 0.20f;

    final PDDocument pdfDocument;

    /**
     * Global, current y location for the current page
     */
    float yPos;

    public Report(final PDDocument pdfDocument) {
        this.pdfDocument = pdfDocument;

        setPageSize(PDRectangle.LETTER, false);
        setTableFont(PDType1Font.HELVETICA);
        setFooterFont(PDType1Font.HELVETICA_OBLIQUE);

        setBaseFontSize(DEFAULT_BASE_FONT_SIZE);
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

    public float getBaseFontSize() {
        return baseFontSize;
    }

    public void setBaseFontSize(float tableFontSize) {
        this.baseFontSize = tableFontSize;
    }

    private float getTableRowHeight() {
        return getBaseFontSize() + 2 * getCellPadding();
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

    private float getFooterFontSize() {
        return (float) Math.ceil(getBaseFontSize() * FOOTER_SCALE);
    }

    public PDFont getFooterFont() {
        return footerFont;
    }

    public void setFooterFont(final PDFont footerFont) {
        this.footerFont = footerFont;
    }

    public void addTable(final AbstractReportTableModel table, final String title, final String subTitle) throws IOException {

        float[] columnWidths = getColumnWidths(table);

        for (final GroupInfo groupInfo : getGroups(table)) {

            int row = 0;  // tracks the last written row

            while (row < table.getRowCount()) {

                final PDPage page = createPage();

                float yStart = 0;   // additional  margin down from the top of the page

                try (final PDPageContentStream contentStream = new PDPageContentStream(pdfDocument, page)) {

                    // add the table title if just starting
                    if (title != null && !title.isEmpty() && row == 0) {
                        yStart += getBaseFontSize() + getMargin();
                        addTableTitle(contentStream, title, subTitle, yStart);
                    }

                    // write a section of the table
                    row = addTableSection(table, groupInfo.group, contentStream, row, columnWidths, yStart);

                } catch (final IOException e) {
                    logSevere(Report.class, e);
                    throw (e);
                }
            }
        }
    }

    public static Set<GroupInfo> getGroups(final AbstractReportTableModel tableModel) {
        final Map<String, GroupInfo> groupInfoMap = new HashMap<>();

        for (int c = 0; c < tableModel.getColumnCount(); c++) {
            final ColumnStyle columnStyle = tableModel.getColumnStyle(c);

            if (columnStyle == ColumnStyle.GROUP || columnStyle == ColumnStyle.GROUP_NO_HEADER) {
                for (int r = 0; r < tableModel.getRowCount(); r++) {
                    final GroupInfo groupInfo = groupInfoMap.computeIfAbsent(tableModel.getValueAt(r, c).toString(),
                            GroupInfo::new);

                    groupInfo.rows++;
                }
            }
        }

        return new TreeSet<>(groupInfoMap.values());
    }

    private int addTableSection(final AbstractReportTableModel table, @NotNull final String group,
                                final PDPageContentStream contentStream, final int startRow, float[] columnWidths,
                                float yStart) throws IOException {

        Objects.requireNonNull(group);

        int rowsWritten = 0;    // the return value of the number of rows written

        float yTop = getPageSize().getHeight() - getMargin() - yStart;

        yPos = yTop;    // start of a new page

        float xPos = getMargin() + getCellPadding();

        yPos = yPos - (getTableRowHeight() / 2)
                - ((getTableFont().getFontDescriptor().getFontBoundingBox().getHeight() / 1000 * getBaseFontSize()) / 4);

        contentStream.setFont(getHeaderFont(), getBaseFontSize());

        // add the header
        contentStream.setNonStrokingColor(headerBackground);
        fillRect(contentStream, getMargin(), yTop - getTableRowHeight(), getAvailableWidth(), getTableRowHeight());

        contentStream.setNonStrokingColor(headerTextColor);

        int col = 0;
        for (int i = 0; i < table.getColumnCount(); i++) {
            if (table.isColumnVisible(i)) {
                drawText(contentStream, xPos, yPos, table.getColumnName(i));
                xPos += columnWidths[col];

                col++;
            }
        }

        // add the rows
        contentStream.setFont(getTableFont(), getBaseFontSize());
        contentStream.setNonStrokingColor(Color.BLACK);

        int row = startRow;

        while (yPos > getMargin() + getTableRowHeight() && row < table.getRowCount()) {

            final String rowGroup = table.getGroup(row);

            if (group.equals(rowGroup)) {

                xPos = getMargin() + getCellPadding();
                yPos -= getTableRowHeight();

                col = 0;
                for (int i = 0; i < table.getColumnCount(); i++) {

                    if (table.isColumnVisible(i)) {

                        final Object value = table.getValueAt(row, i);

                        if (value != null) {
                            float shift = 0;
                            float availWidth = columnWidths[col] - getCellPadding() * 2;

                            final String text = truncateText(formatValue(table.getValueAt(row, i), i, table), availWidth,
                                    getTableFont(), getBaseFontSize());

                            if (rightAlign(i, table)) {
                                shift = availWidth - getStringWidth(text, getTableFont(), getBaseFontSize());
                            }

                            drawText(contentStream, xPos + shift, yPos, text);
                        }

                        xPos += columnWidths[col];

                        col++;
                    }
                }

                rowsWritten++;
            }
            row++;
        }

        // add row lines
        yPos = yTop;
        xPos = getMargin();

        for (int r = 0; r <= rowsWritten + 1; r++) {
            drawLine(contentStream, xPos, yPos, getAvailableWidth() + getMargin(), yPos);
            yPos -= getTableRowHeight();
        }

        // add column lines
        yPos = yTop;
        xPos = getMargin();

        col = 0;
        for (int i = 0; i < table.getColumnCount(); i++) {
            if (table.isColumnVisible(i)) {
                drawLine(contentStream, xPos, yPos, xPos, yPos - getTableRowHeight() * (rowsWritten + 1));
                xPos += columnWidths[col];

                col++;
            }
        }

        // end of last column
        drawLine(contentStream, xPos, yPos, xPos, yPos - getTableRowHeight() * (rowsWritten + 1));

        return row;
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
        contentStream.setLineWidth(DEFAULT_LINE_WIDTH);
        contentStream.moveTo(xStart, yStart);
        contentStream.lineTo(xEnd, yEnd);
        contentStream.stroke();
    }

    private void fillRect(final PDPageContentStream contentStream, final float x, final float y, final float width,
                          final float height) throws IOException {
        contentStream.addRect(x, y, width, height);
        contentStream.fill();
    }

    private float getAvailableWidth() {
        return getPageSize().getWidth() - getMargin() * 2;
    }

    /**
     * Adds a Title to the document and returns the height consumed
     *
     * @param title   title
     * @param yMargin start
     * @throws IOException exception
     */
    private void addTableTitle(final PDPageContentStream contentStream, final String title, final String subTitle,
                               final float yMargin) throws IOException {

        float width = getStringWidth(title, getHeaderFont(), getBaseFontSize() * 2);
        float xPos = (getAvailableWidth() / 2f) - (width / 2f) + getMargin();
        float yPos = getPageSize().getHeight() - yMargin;

        contentStream.setFont(getHeaderFont(), getBaseFontSize() * 2);
        drawText(contentStream, xPos, yPos, title);

        width = getStringWidth(subTitle, getFooterFont(), getFooterFontSize());
        xPos = (getAvailableWidth() / 2f) - (width / 2f) + getMargin();
        yPos = yPos - getFooterFontSize() * 1.5f;

        contentStream.setFont(getFooterFont(), getFooterFontSize());
        drawText(contentStream, xPos, yPos, subTitle);

    }

    public void addFooter() throws IOException {

        final String timeStamp = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).format(LocalDateTime.now());

        final int pageCount = pdfDocument.getNumberOfPages();
        float yStart = getMargin() * 2 / 3;

        for (int i = 0; i < pageCount; i++) {
            final PDPage page = pdfDocument.getPage(i);
            final String pageText = MessageFormat.format(rb.getString("Pattern.Pages"), i + 1, pageCount);
            final float width = getStringWidth(pageText, getFooterFont(), getFooterFontSize());

            try (final PDPageContentStream contentStream = new PDPageContentStream(pdfDocument, page, PDPageContentStream.AppendMode.APPEND, true)) {
                contentStream.setFont(getFooterFont(), getFooterFontSize());

                drawText(contentStream, getMargin(), yStart, timeStamp);
                drawText(contentStream, getPageSize().getWidth() - getMargin() - width, yStart, pageText);
            } catch (final IOException e) {
                logSevere(Report.class, e);
            }
        }
    }

    private String truncateText(final String text, final float availWidth, final PDFont font, final float fontSize) throws IOException {
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

        pdfDocument.addPage(page);  // add the page to the document

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

        int visibleColumnCount = 0;

        for (int i = 0; i < table.getColumnCount(); i++) {
            if (table.isColumnVisible(i)) {
                visibleColumnCount++;
            }
        }

        float[] widths = new float[visibleColumnCount]; // calculated optimal widths

        float measuredWidth = 0;
        float fixedWidth = 0;
        boolean compressAll = false;
        int flexColumns = 0;

        int col = 0;
        for (int i = 0; i < table.getColumnCount(); i++) {
            if (table.isColumnVisible(i)) {

                final String protoValue = table.getColumnPrototypeValueAt(i);

                float headerWidth = getStringWidth(table.getColumnName(i), getHeaderFont(), getBaseFontSize()) + getCellPadding() * 2;
                float cellTextWidth = getStringWidth(protoValue, getTableFont(), getBaseFontSize()) + getCellPadding() * 2;

                widths[col] = Math.max(headerWidth, cellTextWidth);

                measuredWidth += widths[col];

                if (table.isColumnFixedWidth(i)) {
                    fixedWidth += widths[col];
                } else {
                    flexColumns++;
                }

                col++;
            }
        }

        if (fixedWidth > getAvailableWidth()) {
            compressAll = true;
            flexColumns = visibleColumnCount;
        }

        float widthDelta;

        if (compressAll) {  // make it ugly
            widthDelta = (getAvailableWidth() - measuredWidth) / visibleColumnCount;
        } else {
            widthDelta = (getAvailableWidth() - measuredWidth) / flexColumns;
        }

        col = 0;
        for (int i = 0; i < table.getColumnCount(); i++) {
            if (table.isColumnVisible(i)) {
                if (compressAll || !table.isColumnFixedWidth(i)) {
                    widths[col] += widthDelta;
                }
                col++;
            }
        }

        return widths;
    }

    /**
     * Support class for reporting the number of groups and rows per group within a report table.
     */
    public static class GroupInfo implements Comparable<GroupInfo> {
        final String group;
        public int rows;

        GroupInfo(@NotNull String group) {
            Objects.requireNonNull(group);

            this.group = group;
        }

        @Override
        public int compareTo(final GroupInfo o) {
            return group.compareTo(o.group);
        }

        @Override
        public boolean equals(final Object o) {
            if (o instanceof GroupInfo) {
                return group.equals(((GroupInfo) o).group);
            }
            return false;
        }

        public int hashCode() {
            return group.hashCode();
        }
    }
}
