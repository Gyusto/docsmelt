package com.docsmelt.pdf;

import com.docsmelt.format.Format;
import com.docsmelt.io.ConversionException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDChoice;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDPushButton;
import org.apache.pdfbox.pdmodel.interactive.form.PDRadioButton;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads and fills the fillable fields of a PDF form (AcroForm). Optionally
 * "flattens" the result so the answers become part of the page and can no
 * longer be changed.
 */
public final class PdfFormFiller {

    public enum Type { TEXT, CHECKBOX, RADIO, CHOICE }

    /** One fillable field, as shown to the user. */
    public record Field(String name, String label, Type type, String value, List<String> options,
                        boolean multiline, boolean readOnly, boolean required, int maxLength) {
    }

    public List<Field> fields(Path source) {
        try (PDDocument pdf = load(source)) {
            PDAcroForm form = form(pdf);
            List<Field> fields = new ArrayList<>();
            for (PDField f : form.getFieldTree()) {
                Field field = describe(f);
                if (field != null) {
                    fields.add(field);
                }
            }
            if (fields.isEmpty()) {
                throw new ConversionException("This PDF has no fillable fields");
            }
            return fields;
        } catch (IOException e) {
            throw new ConversionException("Could not read the form: " + e.getMessage(), e);
        }
    }

    /** @return number of fields changed */
    public int fill(Path source, Path target, Map<String, String> values, boolean flatten) {
        if (source.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())) {
            throw new ConversionException("Output would overwrite the input: " + source);
        }
        try (PDDocument pdf = load(source)) {
            PDAcroForm form = form(pdf);
            int changed = 0;
            for (var entry : values.entrySet()) {
                PDField field = form.getField(entry.getKey());
                if (field == null) {
                    throw new ConversionException("The form has no field named '" + entry.getKey() + "'");
                }
                if (field.isReadOnly()) {
                    continue;
                }
                try {
                    set(field, entry.getValue());
                    changed++;
                } catch (IOException | IllegalArgumentException e) {
                    throw new ConversionException("Field '" + entry.getKey() + "': " + e.getMessage(), e);
                }
            }
            if (flatten) {
                form.flatten();
            }
            Files.createDirectories(target.toAbsolutePath().getParent());
            pdf.save(target.toFile());
            return changed;
        } catch (IOException e) {
            throw new ConversionException("Could not fill the form: " + e.getMessage(), e);
        }
    }

    private static PDDocument load(Path source) {
        if (Format.fromPath(source) != Format.PDF) {
            throw new ConversionException("Only PDF forms can be filled: " + source.getFileName());
        }
        return PdfMerger.load(source, source);
    }

    private static PDAcroForm form(PDDocument pdf) {
        PDAcroForm form = pdf.getDocumentCatalog().getAcroForm();
        if (form == null) {
            throw new ConversionException("This PDF has no fillable fields");
        }
        if (form.xfaIsDynamic()) {
            throw new ConversionException("This is a dynamic XFA form, which can only be filled in Adobe Reader");
        }
        if (form.hasXFA()) {
            form.setXFA(null); // static XFA duplicates the AcroForm; drop it so viewers show our values
        }
        return form;
    }

    private static Field describe(PDField f) throws IOException {
        String name = f.getFullyQualifiedName();
        String label = f.getAlternateFieldName() != null && !f.getAlternateFieldName().isBlank()
                ? f.getAlternateFieldName() : f.getPartialName();
        boolean required = f.isRequired();
        boolean readOnly = f.isReadOnly();
        return switch (f) {
            case PDTextField t -> new Field(name, label, Type.TEXT, nz(t.getValue()), List.of(), t.isMultiline(),
                    readOnly, required, t.getMaxLen());
            case PDCheckBox c -> new Field(name, label, Type.CHECKBOX, String.valueOf(c.isChecked()), List.of(),
                    false, readOnly, required, -1);
            case PDRadioButton r -> new Field(name, label, Type.RADIO, nz(r.getValueAsString()),
                    List.copyOf(r.getOnValues()), false, readOnly, required, -1);
            case PDChoice c -> new Field(name, label, Type.CHOICE, c.getValue().isEmpty() ? "" : c.getValue().getFirst(),
                    List.copyOf(c.getOptionsExportValues()), false, readOnly, required, -1);
            case PDPushButton ignored -> null;
            case PDSignatureField ignored -> null;
            default -> null;
        };
    }

    private static void set(PDField field, String value) throws IOException {
        String v = value == null ? "" : value;
        switch (field) {
            case PDCheckBox c -> {
                if ("true".equalsIgnoreCase(v) || "on".equalsIgnoreCase(v) || "yes".equalsIgnoreCase(v)) {
                    c.check();
                } else {
                    c.unCheck();
                }
            }
            case PDRadioButton r -> {
                if (v.isEmpty()) {
                    r.setValue("Off");
                } else if (!r.getOnValues().contains(v)) {
                    throw new IllegalArgumentException("'" + v + "' is not one of " + r.getOnValues());
                } else {
                    r.setValue(v);
                }
            }
            case PDChoice c -> c.setValue(v);
            default -> field.setValue(v);
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
