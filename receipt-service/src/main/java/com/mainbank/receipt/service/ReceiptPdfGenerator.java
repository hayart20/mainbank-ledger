package com.mainbank.receipt.service;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;

@Component
public class ReceiptPdfGenerator {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

    private static final Font TITLE = new Font(Font.HELVETICA, 18, Font.BOLD);
    private static final Font LABEL = new Font(Font.HELVETICA, 10, Font.BOLD);
    private static final Font VALUE = new Font(Font.HELVETICA, 10, Font.NORMAL);
    private static final Font MONO = new Font(Font.COURIER, 8, Font.NORMAL);

    public byte[] generate(ReceiptData d) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 50, 50, 60, 60);
        try {
            PdfWriter.getInstance(doc, out);
            doc.addTitle("Transaction receipt " + d.transferId());
            doc.addAuthor("Mainbank OJSC");
            doc.open();

            Paragraph title = new Paragraph("Mainbank OJSC - Transaction Receipt", TITLE);
            title.setSpacingAfter(16);
            doc.add(title);

            PdfPTable table = new PdfPTable(new float[]{1.2f, 3f});
            table.setWidthPercentage(100);
            row(table, "Transaction ID", d.transferId().toString());
            row(table, "Type", d.type());
            row(table, "Date", TS.format(d.createdAt()));
            row(table, "From", d.sourceOwner() + "\n" + d.sourceIban());
            row(table, "To", d.targetOwner() + "\n" + d.targetIban());
            row(table, "Amount", d.amount().toPlainString() + " " + d.currency());
            row(table, "Status", "COMPLETED");
            doc.add(table);

            Paragraph hashLabel = new Paragraph("SHA-256 of transaction details (CBA digital audit trail)", LABEL);
            hashLabel.setSpacingBefore(24);
            doc.add(hashLabel);
            doc.add(new Paragraph(d.detailsHash(), MONO));

            Paragraph note = new Paragraph("Hash input: " + d.canonical(), MONO);
            note.setSpacingBefore(8);
            doc.add(note);
        } catch (DocumentException e) {
            throw new IllegalStateException("PDF generation failed", e);
        } finally {
            if (doc.isOpen()) doc.close();
        }
        return out.toByteArray();
    }

    private static void row(PdfPTable table, String label, String value) {
        PdfPCell l = new PdfPCell(new Phrase(label, LABEL));
        PdfPCell v = new PdfPCell(new Phrase(latin1(value), VALUE));
        for (PdfPCell c : new PdfPCell[]{l, v}) {
            c.setPadding(6);
            c.setHorizontalAlignment(Element.ALIGN_LEFT);
        }
        table.addCell(l);
        table.addCell(v);
    }

    /** Built-in Helvetica only covers Latin-1; replace anything else instead of emitting garbage glyphs. */
    private static String latin1(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> sb.append(cp < 256 ? (char) cp : '?'));
        return sb.toString();
    }
}
