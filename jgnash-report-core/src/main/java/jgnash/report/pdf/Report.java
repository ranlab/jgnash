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
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.Color;
import java.awt.image.BufferedImage;

import java.io.IOException;
import java.math.BigDecimal;
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
 * The origin of a PDFBox page is the bottom left corner vs. a report being created from the top down.  Report layout
 * logic is from top down with use of a method to convert to PDF coordinate system.
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

    final Color footerBackGround = Color.LIGHT_GRAY;

    final Color headerBackground = Color.DARK_GRAY;

    final Color headerTextColor = Color.WHITE;

    final float FOOTER_SCALE = 0.80f;

    final float DEFAULT_LINE_WIDTH = 0.20f;

    final PDDocument pdfDocument;

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

    public void addTable(final AbstractReportTableModel reportModel, final String title, final String subTitle) throws IOException {

        final float[] columnWidths = getColumnWidths(reportModel);

        final Set<GroupInfo> groupInfoSet = getGroups(reportModel);

        float docY;

        for (final GroupInfo groupInfo : groupInfoSet) {

            int row = 0;  // tracks the last written row

            while (row < reportModel.getRowCount()) {

                final PDPage page = createPage();

                docY = getMargin();   // start at top of the page with the margin

                try (final PDPageContentStream contentStream = new PDPageContentStream(pdfDocument, page)) {

                    // add the table title if just starting and it's the 1st page of the report
                    if (title != null && !title.isEmpty() && row == 0 && pdfDocument.getNumberOfPages() == 1) {
                        docY = addReportTitle(contentStream, title, subTitle, docY);
                    }

                    // add the group subtitle if needed
                    if (groupInfoSet.size() > 1) {
                        docY = addTableTitle(contentStream, groupInfo.group, docY);
                    }

                    // write a section of the table and save the last row written for next page if needed
                    final Pair<Integer, Float> pair
                            = addTableSection(reportModel, groupInfo.group, contentStream, row, columnWidths, docY);

                    row = pair.getLeft();
                    docY = pair.getRight();

                } catch (final IOException e) {
                    logSevere(Report.class, e);
                    throw (e);
                }

                // check to see if this table has summation information and add a summation footer
                if (groupInfo.hasSummation() && row == reportModel.getRowCount()) {

                    // TODO, make sure the end of the page has not been reached
                    try (final PDPageContentStream contentStream = new PDPageContentStream(pdfDocument, page, PDPageContentStream.AppendMode.APPEND, false)) {
                        docY = addTableFooter(reportModel, groupInfo, contentStream, columnWidths, docY);
                    } catch (final IOException e) {
                        logSevere(Report.class, e);
                        throw (e);
                    }
                }
            }
        }
    }

    /**
     * Simply transform function to convert from a upper origin to a lower pdf page origin.
     * @param y document y position
     * @return returns the pdf page y position
     */
    private float docYToPageY(final float y) {
        return getPageSize().getHeight() - y;
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

        // perform summation
        for (int r = 0; r < tableModel.getRowCount(); r++) {
            final GroupInfo groupInfo = groupInfoMap.get(tableModel.getGroup(r));

            for (int c = 0; c < tableModel.getColumnCount(); c++) {
                if (tableModel.getColumnClass(c) == BigDecimal.class) {
                    switch (tableModel.getColumnStyle(c)) {
                        case AMOUNT_SUM:
                        case BALANCE_WITH_SUM:
                        case BALANCE_WITH_SUM_AND_GLOBAL:
                            groupInfo.addValue(c, (BigDecimal) tableModel.getValueAt(r, c));
                        default:
                            break;
                    }
                }
            }
        }

        return new TreeSet<>(groupInfoMap.values());
    }

    /**
     * Writes a table section to the report.
     *
     * @param reportModel report model
     * @param group report group
     * @param contentStream PDF content stream
     * @param startRow starting row
     * @param columnWidths column widths
     * @param yStart start location from top of the page
     * @return returns the last reported row of the group and yDoc location
     * @throws IOException  IO exception
     */
    @SuppressWarnings("SuspiciousNameCombination")
    private Pair<Integer, Float> addTableSection(final AbstractReportTableModel reportModel, @NotNull final String group,
                                                 final PDPageContentStream contentStream, final int startRow, float[] columnWidths,
                                                 float yStart) throws IOException {

        Objects.requireNonNull(group);

        int rowsWritten = 0;    // the return value of the number of rows written

        // establish start location, use half the row height as the vertical margin between title and table
        final float yTop = getPageSize().getHeight() - getTableRowHeight() / 2 - yStart;

        float xPos = getMargin() + getCellPadding();
        float yPos = yTop - getTableRowHeight() + getRowTextBaselineOffset();

        contentStream.setFont(getHeaderFont(), getBaseFontSize());

        // add the header
        contentStream.setNonStrokingColor(headerBackground);
        fillRect(contentStream, getMargin(), yTop - getTableRowHeight(), getAvailableWidth(), getTableRowHeight());

        contentStream.setNonStrokingColor(headerTextColor);

        int col = 0;
        for (int i = 0; i < reportModel.getColumnCount(); i++) {
            if (reportModel.isColumnVisible(i)) {
                drawText(contentStream, xPos, yPos, reportModel.getColumnName(i));
                xPos += columnWidths[col];

                col++;
            }
        }

        // add the rows
        contentStream.setFont(getTableFont(), getBaseFontSize());
        contentStream.setNonStrokingColor(Color.BLACK);

        int row = startRow;

        while (yPos > getMargin() + getTableRowHeight() && row < reportModel.getRowCount()) {

            final String rowGroup = reportModel.getGroup(row);

            if (group.equals(rowGroup)) {

                xPos = getMargin() + getCellPadding();
                yPos -= getTableRowHeight();

                col = 0;
                for (int i = 0; i < reportModel.getColumnCount(); i++) {

                    if (reportModel.isColumnVisible(i)) {

                        final Object value = reportModel.getValueAt(row, i);

                        if (value != null) {
                            float shift = 0;
                            float availWidth = columnWidths[col] - getCellPadding() * 2;

                            final String text = truncateText(formatValue(reportModel.getValueAt(row, i), i, reportModel), availWidth,
                                    getTableFont(), getBaseFontSize());

                            if (rightAlign(i, reportModel)) {
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
        for (int i = 0; i < reportModel.getColumnCount(); i++) {
            if (reportModel.isColumnVisible(i)) {
                drawLine(contentStream, xPos, yPos, xPos, yPos - getTableRowHeight() * (rowsWritten + 1));
                xPos += columnWidths[col];

                col++;
            }
        }

        // end of last column
        drawLine(contentStream, xPos, yPos, xPos, yPos - getTableRowHeight() * (rowsWritten + 1));

        float yDoc = getPageSize().getHeight() - (yPos - getTableRowHeight() * (rowsWritten + 1));

        // return the row and docY position
        return new ImmutablePair<>(row, yDoc);
    }

    /**
     * Calculates the offset for row text
     * @return offset
     */
    private float getRowTextBaselineOffset() {
        return getTableRowHeight()
                - getTableFont().getFontDescriptor().getFontBoundingBox().getHeight() / 1000 * getBaseFontSize();
    }

    /**
     * Writes a table footer to the report.
     *
     * @param reportModel report model
     * @param groupInfo Group info to report on*
     * @param contentStream PDF content stream
     * @param columnWidths column widths
     * @param yStart start location from top of the page
     * @return returns the y position from the top of the page
     * @throws IOException  IO exception
     */
    private float addTableFooter(final AbstractReportTableModel reportModel, final GroupInfo groupInfo,
                                final PDPageContentStream contentStream, float[] columnWidths,
                                float yStart) throws IOException {

        float yDoc = yStart + getTableRowHeight();

        // add the footer background
        contentStream.setNonStrokingColor(footerBackGround);
        fillRect(contentStream, getMargin(), docYToPageY(yDoc), getAvailableWidth(), getTableRowHeight());

        drawLine(contentStream, getMargin(), docYToPageY(yDoc), getAvailableWidth() + getMargin(), docYToPageY(yDoc));
        drawLine(contentStream, getMargin(), docYToPageY(yDoc - getTableRowHeight()), getMargin(), docYToPageY(yDoc));
        drawLine(contentStream, getMargin() + getAvailableWidth(), docYToPageY(yDoc - getTableRowHeight()),
                getMargin() + getAvailableWidth(), docYToPageY(yDoc));


        contentStream.setFont(getTableFont(), getBaseFontSize());
        contentStream.setNonStrokingColor(Color.BLACK);

        // draw summation values
        float xPos = getMargin() + getCellPadding();

        drawText(contentStream, xPos, docYToPageY(yDoc - getRowTextBaselineOffset()), reportModel.getGroupFooterLabel());

        for (int c = 0; c < reportModel.getColumnCount(); c++) {

            if (reportModel.isColumnVisible(c) && reportModel.isColumnSummed(c)) {

                final Object value = groupInfo.getValue(c);

                if (value != null) {
                    float shift = 0;
                    float availWidth = columnWidths[c] - getCellPadding() * 2;

                    final String text = truncateText(formatValue(groupInfo.getValue(c), c, reportModel), availWidth,
                            getTableFont(), getBaseFontSize());

                    if (rightAlign(c, reportModel)) {
                        shift = availWidth - getStringWidth(text, getTableFont(), getBaseFontSize());
                    }

                    drawText(contentStream, xPos + shift, docYToPageY(yDoc - getRowTextBaselineOffset()), text);
                }
            }

            if (c < reportModel.getColumnCount() - 1) {
                xPos += columnWidths[c];
            }
        }

        return 0;

    }

    private String formatValue(final Object value, final int column, final AbstractReportTableModel reportModel) {
        if (value == null) {
            return " ";
        }

        final ColumnStyle columnStyle = reportModel.getColumnStyle(column);

        switch (columnStyle) {
            case TIMESTAMP:
                final DateTimeFormatter dateTimeFormatter = DateUtils.getShortDateTimeFormatter();
                return dateTimeFormatter.format((LocalDateTime) value);
            case SHORT_DATE:
                final DateTimeFormatter dateFormatter = DateUtils.getShortDateFormatter();
                return dateFormatter.format((LocalDate) value);
            case SHORT_AMOUNT:
                final NumberFormat shortNumberFormat = CommodityFormat.getShortNumberFormat(reportModel.getCurrency());
                return shortNumberFormat.format(value);
            case BALANCE:
            case BALANCE_WITH_SUM:
            case BALANCE_WITH_SUM_AND_GLOBAL:
            case AMOUNT_SUM:
                final NumberFormat numberFormat = CommodityFormat.getFullNumberFormat(reportModel.getCurrency());
                return numberFormat.format(value);
            default:
                return value.toString();
        }
    }

    private boolean rightAlign(final int column, final AbstractReportTableModel reportModel) {
        final ColumnStyle columnStyle = reportModel.getColumnStyle(column);

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
     * Adds a title to the table and returns the new document position
     *
     * @param title title
     * @param yStart  table title position from the top of the page
     * @return current y document position
     * @throws IOException exception
     */
    private float addTableTitle(final PDPageContentStream contentStream, final String title, final float yStart)
            throws IOException {

        float docY = yStart + getBaseFontSize() * 1.5f;  // add for font height
        float xPos = getMargin();

        contentStream.setFont(getHeaderFont(), getBaseFontSize() * 1.5f);
        drawText(contentStream, xPos, docYToPageY(docY), title);

        return docY;    // returns new y document position
    }

    /**
     * Adds a Title and subtitle to the document and returns the height consumed
     *
     * @param title   title
     * @param yStart start from the top of the page
     * @return document y position
     * @throws IOException exception
     */
    private float addReportTitle(final PDPageContentStream contentStream, final String title, final String subTitle,
                                final float yStart) throws IOException {

        float width = getStringWidth(title, getHeaderFont(), getBaseFontSize() * 2);
        float xPos = (getAvailableWidth() / 2f) - (width / 2f) + getMargin();
        float docY = yStart + getBaseFontSize();

        contentStream.setFont(getHeaderFont(), getBaseFontSize() * 2);
        drawText(contentStream, xPos, docYToPageY(docY), title);

        width = getStringWidth(subTitle, getFooterFont(), getFooterFontSize());
        xPos = (getAvailableWidth() / 2f) - (width / 2f) + getMargin();
        docY += getFooterFontSize() * 1.5f;

        contentStream.setFont(getFooterFont(), getFooterFontSize());
        drawText(contentStream, xPos, docYToPageY(docY), subTitle);

        docY += getFooterFontSize() * 2.0f;   // add a margin below the sub title

        return docY;
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

    private float[] getColumnWidths(final AbstractReportTableModel reportModel) throws IOException {

        int visibleColumnCount = 0;

        for (int i = 0; i < reportModel.getColumnCount(); i++) {
            if (reportModel.isColumnVisible(i)) {
                visibleColumnCount++;
            }
        }

        float[] widths = new float[visibleColumnCount]; // calculated optimal widths

        float measuredWidth = 0;
        float fixedWidth = 0;
        boolean compressAll = false;
        int flexColumns = 0;

        int col = 0;
        for (int i = 0; i < reportModel.getColumnCount(); i++) {
            if (reportModel.isColumnVisible(i)) {

                final String protoValue = reportModel.getColumnPrototypeValueAt(i);

                float headerWidth = getStringWidth(reportModel.getColumnName(i), getHeaderFont(), getBaseFontSize()) + getCellPadding() * 2;
                float cellTextWidth = getStringWidth(protoValue, getTableFont(), getBaseFontSize()) + getCellPadding() * 2;

                widths[col] = Math.max(headerWidth, cellTextWidth);

                measuredWidth += widths[col];

                if (reportModel.isColumnFixedWidth(i)) {
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
        for (int i = 0; i < reportModel.getColumnCount(); i++) {
            if (reportModel.isColumnVisible(i)) {
                if (compressAll || !reportModel.isColumnFixedWidth(i)) {
                    widths[col] += widthDelta;
                }
                col++;
            }
        }

        return widths;
    }

    /**
     * Renders the PDF report to a raster image
     * @param pageIndex page index
     * @param dpi DPI for the image
     * @return the image
     * @throws IOException
     */
    public BufferedImage renderImage(final int pageIndex, final int dpi) throws IOException {
        final PDFRenderer pdfRenderer = new PDFRenderer(pdfDocument);
        return pdfRenderer.renderImageWithDPI(pageIndex, dpi, ImageType.RGB);
    }

    /**
     * Support class for reporting the number of groups and rows per group within a report table.
     */
    public static class GroupInfo implements Comparable<GroupInfo> {

        /**
         * Group name
         */
        final String group;

        /**
         * Number of rows in the group
         */
        public int rows;

        /**
         * Summation values for cross tabulation of columns
         */
        final Map<Integer, BigDecimal> summationMap = new HashMap<>();

        private boolean hasSummation = false;

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

        public void addValue(final int column, final BigDecimal value) {
            summationMap.put(column, getValue(column).add(value));

            hasSummation = true;
        }

        @NotNull
        private BigDecimal getValue(final int column) {
            return summationMap.getOrDefault(column, BigDecimal.ZERO);
        }

        public boolean hasSummation() {
            return hasSummation;
        }

        public int hashCode() {
            return group.hashCode();
        }
    }
}
