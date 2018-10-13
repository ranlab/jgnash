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

import java.io.IOException;

import static jgnash.util.LogUtil.logSevere;

/**
 * Base report format definition.
 *
 * Units of measure is in Points
 *
 * @author Craig Cavanaugh
 *
 * TODO: Footer, full margin control
 */
public class Report {

    private PDRectangle pageSize;

    private float tableFontSize;

    private PDFont tableFont;

    private PDFont headerFont;

    private float margin;

    private float cellPadding = 2;

    public Report() {
        pageSize = PDRectangle.LETTER;
        tableFont = PDType1Font.HELVETICA;
        tableFontSize = 12;
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
        return  pageSize.getWidth() > pageSize.getHeight();
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

    public void addTable(final PDDocument doc, final AbstractReportTableModel table) {

        // TODO Allow for summation row for certain report types
        final int rowsPerPage = (int)Math.floor(getAvailableHeight() / getTableRowHeight()) - 1;
        final int numberOfPages = (int)Math.ceil((float)table.getRowCount()/ (float)rowsPerPage);

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
        for (int i = 0; i < table.getColumnCount(); i++) {
            contentStream.beginText();
            contentStream.newLineAtOffset(xPos, yPos);
            contentStream.showText(table.getColumnName(i));
            contentStream.endText();

            xPos += columnWidth;
        }

        // add the rows
        contentStream.setFont(getTableFont(), getTableFontSize());

        for (int i = 0; i < rows; i++) {
            int row = i + startRow;

            xPos = getMargin() + getCellPadding();
            yPos -= getTableRowHeight();

            for (int j = 0; j < table.getColumnCount(); j++) {

                final Object value = table.getValueAt(row, j);

                if (value != null) {
                    contentStream.beginText();
                    contentStream.newLineAtOffset(xPos, yPos);
                    contentStream.showText(table.getValueAt(row, j).toString());
                    contentStream.endText();
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


    private void drawLine(final PDPageContentStream contentStream, final float xStart, final float yStart,
                          final float xEnd, final float yEnd) throws IOException {
        contentStream.moveTo(xStart, yStart);
        contentStream.lineTo(xEnd, yEnd);
        contentStream.stroke();
    }

    private float getAvailableHeight() {
        return getPageSize().getHeight() - getMargin() * 2;
    }

    private float getAvailableWidth() {
        return getPageSize().getWidth() - getMargin() * 2;
    }

    private PDPage createPage() {
        PDPage page = new PDPage();
        page.setMediaBox(getPageSize());


        // TODO: Add page footer here
        return page;
    }
}
