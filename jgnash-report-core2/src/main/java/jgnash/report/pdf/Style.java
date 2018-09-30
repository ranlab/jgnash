package jgnash.report.pdf;

import com.lowagie.text.Element;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;

import java.awt.Color;

public class Style {

    public static void headerCellStyle(final PdfPCell cell) {

        // alignment
        cell.setHorizontalAlignment(Element.ALIGN_LEFT);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);

        // padding
        cell.setPaddingLeft(2);
        cell.setPaddingRight(2);
        //cell.setPaddingBottom(2);

        // background color
        cell.setBackgroundColor(Color.DARK_GRAY);

        // border
        cell.setBorder(Rectangle.BOX);
        cell.setBorderColor(Color.DARK_GRAY);

        // height
        cell.setFixedHeight(16);    // size is in points (2X the font height)

    }

    public static void valueCellStyle(final PdfPCell cell) {
        // alignment
        cell.setHorizontalAlignment(Element.ALIGN_LEFT);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);

        // padding
        cell.setPadding(2);

        // border
        cell.setBorder(Rectangle.BOTTOM + Rectangle.TOP);

        // height
        cell.setFixedHeight(16);  // size is in points (2X the font height)
    }

    public static void numericValueCellStyle(final PdfPCell cell) {
        valueCellStyle(cell);

        // alignment
        cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
    }
}
