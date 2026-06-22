package com.example.jobapp.service;

import com.example.jobapp.model.UploadedFile;
import com.example.jobapp.repository.UploadedFileRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class FileUploadService {

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    );

    private final UploadedFileRepository uploadedFileRepository;
    private final Path uploadDirectory;

    public FileUploadService(UploadedFileRepository uploadedFileRepository,
                             @Value("${jobapp.upload.dir:uploads}") String uploadDir) throws IOException {
        this.uploadedFileRepository = uploadedFileRepository;
        this.uploadDirectory = Paths.get(uploadDir).toAbsolutePath().normalize();
        Files.createDirectories(this.uploadDirectory);
    }

    public UploadedFile store(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File is empty.");
        }

        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) {
            throw new IllegalArgumentException("File name is missing.");
        }

        String lowerName = originalName.toLowerCase(Locale.ROOT);
        if (!lowerName.endsWith(".pdf") && !lowerName.endsWith(".docx")) {
            throw new IllegalArgumentException("Unsupported file type. Upload a .pdf or .docx file.");
        }

        String contentType = file.getContentType();
        if (contentType != null && !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("Unsupported file type. Upload a .pdf or .docx file.");
        }

        String storedFileName = UUID.randomUUID() + extractExtension(lowerName);
        Path target = uploadDirectory.resolve(storedFileName);
        Files.copy(file.getInputStream(), target);

        UploadedFile entity = new UploadedFile();
        entity.setOriginalFileName(originalName);
        entity.setStoredFileName(storedFileName);
        entity.setContentType(contentType != null ? contentType : "application/octet-stream");
        entity.setSizeBytes(file.getSize());
        entity.setUploadedAt(Instant.now());

        return uploadedFileRepository.save(entity);
    }

    private String extractExtension(String lowerName) {
        int dot = lowerName.lastIndexOf('.');
        return dot >= 0 ? lowerName.substring(dot) : "";
    }
}
