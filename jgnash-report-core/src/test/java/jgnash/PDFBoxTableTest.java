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
package jgnash;

import jgnash.engine.CurrencyNode;
import jgnash.engine.DefaultCurrencies;
import jgnash.report.pdf.Report;
import jgnash.report.table.AbstractReportTableModel;
import jgnash.report.table.ColumnHeaderStyle;
import jgnash.report.table.ColumnStyle;
import jgnash.resource.util.ResourceUtils;
import jgnash.util.NotNull;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.tools.imageio.ImageIOUtil;


import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ResourceBundle;

import static org.junit.jupiter.api.Assertions.*;

class PDFBoxTableTest {

    @Test
    void simpleTest() throws IOException {

        Path tempPath = null;

        try (PDDocument doc = new PDDocument()) {
            tempPath = Files.createTempFile("test", ".pdf");
            System.out.println(tempPath);

            PDPage page = new PDPage();
            doc.addPage(page);

            PDFont font = PDType1Font.HELVETICA;

            try (final PDPageContentStream contents = new PDPageContentStream(doc, page)) {
                contents.beginText();
                contents.setFont(font, 11);
                contents.newLineAtOffset(100, 700);
                contents.showText("Hello World!");
                contents.endText();
            }

            doc.save(tempPath.toFile());
        } catch (final IOException e) {
            e.printStackTrace();
        } finally {
            if (tempPath != null) {
                Files.deleteIfExists(tempPath);
            }
        }
    }

    @Test
    void basicReportTest() throws IOException {

        Path tempPath = null;
        Path tempRasterPath = null;

        try (final PDDocument doc = new PDDocument()) {
            tempPath = Files.createTempFile("pdfTest", ".pdf");
            tempRasterPath = Files.createTempFile("pdfTest", ".png");

            final float padding = 2.5f;
            boolean landscape = true;

            final Report report = new Report();
            report.setTableFont(PDType1Font.COURIER);
            report.setHeaderFont(PDType1Font.HELVETICA_BOLD);
            report.setCellPadding(padding);
            report.setTableFontSize(9);
            report.setPageSize(PDRectangle.LETTER, landscape);
            report.setMargin(32);
            report.setFooterFont(PDType1Font.TIMES_ITALIC);
            report.setFooterFontSize(8);
            report.setEllipsis("…");

            report.addTable(doc, new TestReport(), 50);
            report.addFooter(doc);

            doc.save(tempPath.toFile());

            assertEquals(landscape, report.isLandscape());
            assertEquals(padding, report.getCellPadding());
            assertTrue(Files.exists(tempPath));


            // Create a PNG file
            final PDFRenderer pdfRenderer = new PDFRenderer(doc);
            final BufferedImage bim = pdfRenderer.renderImageWithDPI(0, 300, ImageType.RGB);
            ImageIOUtil.writeImage(bim, tempRasterPath.toString(), 300);

            assertTrue(Files.exists(tempRasterPath));

        } catch (final IOException e) {
            e.printStackTrace();
        } finally {
            if (tempPath != null) {
                //Files.deleteIfExists(tempPath);
            }

            if (tempRasterPath != null) {
                Files.deleteIfExists(tempRasterPath);
            }
        }
    }

    private class TestReport extends AbstractReportTableModel {

        private static final String COLUMN_DATE = "Column.Date";
        private static final String COLUMN_NUM = "Column.Num";
        private static final String COLUMN_PAYEE = "Column.Payee";
        private static final String COLUMN_MEMO = "Column.Memo";
        private static final String COLUMN_ACCOUNT = "Column.Account";
        private static final String COLUMN_CLR = "Column.Clr";
        private static final String COLUMN_DEPOSIT = "Column.Deposit";
        private static final String COLUMN_WITHDRAWAL = "Column.Withdrawal";
        private static final String COLUMN_BALANCE = "Column.Balance";
        private static final String COLUMN_TIMESTAMP = "Column.Timestamp";

        private final ResourceBundle rb = ResourceUtils.getBundle();

        private final ColumnStyle[] columnStyles = {ColumnStyle.SHORT_DATE, ColumnStyle.TIMESTAMP,
                ColumnStyle.STRING, ColumnStyle.STRING, ColumnStyle.STRING, ColumnStyle.STRING, ColumnStyle.STRING,
                ColumnStyle.SHORT_AMOUNT, ColumnStyle.SHORT_AMOUNT, ColumnStyle.AMOUNT_SUM};

        private String[] columnNames = {rb.getString(COLUMN_DATE), rb.getString(COLUMN_TIMESTAMP),
                rb.getString(COLUMN_NUM), rb.getString(COLUMN_PAYEE), rb.getString(COLUMN_MEMO),
                rb.getString(COLUMN_ACCOUNT), rb.getString(COLUMN_CLR), rb.getString(COLUMN_DEPOSIT),
                rb.getString(COLUMN_WITHDRAWAL), rb.getString(COLUMN_BALANCE)};

        final CurrencyNode currencyNode;

        TestReport() {
            currencyNode = DefaultCurrencies.getDefault();
        }


        @Override
        public CurrencyNode getCurrency() {
            return currencyNode;
        }

        @Override
        public ColumnStyle getColumnStyle(int columnIndex) {
            return columnStyles[columnIndex];
        }

        @Override
        public ColumnHeaderStyle getColumnHeaderStyle(int columnIndex) {
            if (columnIndex < 6) {
                return ColumnHeaderStyle.LEFT;
            }

            return ColumnHeaderStyle.RIGHT;
        }

        @Override
        public Class<?> getColumnClass(final int columnIndex) {

            switch (columnIndex) {
                case 0:
                    return LocalDate.class;
                case 1:
                    return LocalDateTime.class;
                case 2:
                case 3:
                case 4:
                case 5:
                case 6:
                    return String.class;
                default:
                    return BigDecimal.class;
            }
        }

        @Override
        public boolean isColumnFixedWidth(final int columnIndex) {
            switch (columnIndex) {
                case 0:
                case 1:
                case 2:
                case 6:
                case 7:
                case 8:
                case 9:
                    return true;
                default:
                    return false;
            }
        }

        @Override
        @NotNull
        public String getColumnName(final int columnIndex) {
            return columnNames[columnIndex];
        }

        @Override
        public int getRowCount() {
            return 80;
        }

        @Override
        public int getColumnCount() {
            return columnNames.length;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {

            switch (columnIndex) {
                case 0:
                    return LocalDate.now().plusDays(rowIndex);
                case 1:
                    return LocalDateTime.now().plusDays(rowIndex);
                case 2:
                    return Integer.toString(1000 + rowIndex);
                case 3:
                    return "Payee " + rowIndex;
                case 4:
                    return "A Typical Memo " + rowIndex;
                case 5:
                    return "Account " + rowIndex;
                case 6:
                    return "R";
                case 7:
                    return rowIndex % 2 == 0 ? new BigDecimal(100 + rowIndex) : null;
                case 8:
                    return rowIndex % 2 != 0 ? new BigDecimal(100 + rowIndex) : null;
                case 9:
                    return new BigDecimal(1000 + rowIndex * 10);
                default:
                    return null;
            }
        }
    }

}