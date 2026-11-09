package com.docsmelt.pdf;

import com.docsmelt.format.Format;
import com.docsmelt.io.ConversionException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Adds or removes PDF passwords. Protection uses AES-256, the strongest
 * standard PDF encryption.
 */
public final class PdfSecurity {

    /**
     * What people who open the file with the (user) password may do. Viewers
     * enforce these; they are not a security boundary on their own.
     */
    public record Permissions(boolean print, boolean copy, boolean modify) {
        public static final Permissions ALL = new Permissions(true, true, true);
    }

    private static final int MIN_PASSWORD = 4;
    private static final int MAX_PASSWORD = 127; // AES-256 PDF passwords are truncated at 127 bytes

    /**
     * @param ownerPassword unlocks everything; null = a random one nobody knows,
     *                      so the permissions can't be lifted with the user password
     */
    public void protect(Path source, Path target, String userPassword, String ownerPassword, Permissions permissions) {
        requirePdf(source, target);
        validate(userPassword);
        if (ownerPassword != null && !ownerPassword.isEmpty()) {
            validate(ownerPassword);
        } else {
            byte[] random = new byte[24];
            new SecureRandom().nextBytes(random);
            ownerPassword = HexFormat.of().formatHex(random);
        }
        try (PDDocument pdf = load(source, null)) {
            if (pdf.isEncrypted()) {
                throw new ConversionException("This PDF is already password-protected; unlock it first");
            }
            AccessPermission access = new AccessPermission();
            access.setCanPrint(permissions.print());
            access.setCanPrintFaithful(permissions.print());
            access.setCanExtractContent(permissions.copy());
            access.setCanModify(permissions.modify());
            access.setCanModifyAnnotations(permissions.modify());
            access.setCanFillInForm(true);
            access.setCanExtractForAccessibility(true); // screen readers keep working

            StandardProtectionPolicy policy = new StandardProtectionPolicy(ownerPassword, userPassword, access);
            policy.setEncryptionKeyLength(256);
            pdf.protect(policy);
            save(pdf, target);
        } catch (IOException e) {
            throw new ConversionException("Could not protect " + source.getFileName() + ": " + e.getMessage(), e);
        }
    }

    /** Removes the password (user or owner password accepted). */
    public void unlock(Path source, Path target, String password) {
        requirePdf(source, target);
        try (PDDocument pdf = load(source, password == null ? "" : password)) {
            if (!pdf.isEncrypted()) {
                throw new ConversionException("This PDF isn't password-protected");
            }
            pdf.setAllSecurityToBeRemoved(true);
            save(pdf, target);
        } catch (IOException e) {
            throw new ConversionException("Could not unlock " + source.getFileName() + ": " + e.getMessage(), e);
        }
    }

    private static PDDocument load(Path source, String password) {
        try {
            return password == null ? Loader.loadPDF(source.toFile()) : Loader.loadPDF(source.toFile(), password);
        } catch (InvalidPasswordException e) {
            throw new ConversionException(password == null || password.isEmpty()
                    ? "This PDF is password-protected; unlock it first"
                    : "Wrong password", e);
        } catch (IOException e) {
            throw new ConversionException("Could not read PDF " + source.getFileName() + ": " + e.getMessage(), e);
        }
    }

    private static void save(PDDocument pdf, Path target) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        pdf.save(target.toFile());
    }

    private static void validate(String password) {
        if (password == null || password.length() < MIN_PASSWORD) {
            throw new IllegalArgumentException("Password must be at least " + MIN_PASSWORD + " characters");
        }
        if (password.length() > MAX_PASSWORD) {
            throw new IllegalArgumentException("Password must be at most " + MAX_PASSWORD + " characters");
        }
    }

    private static void requirePdf(Path source, Path target) {
        if (Format.fromPath(source) != Format.PDF) {
            throw new ConversionException("Only PDF files can be protected or unlocked: " + source.getFileName());
        }
        if (source.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())) {
            throw new ConversionException("Output would overwrite the input: " + source);
        }
    }
}
