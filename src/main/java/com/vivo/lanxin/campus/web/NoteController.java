package com.vivo.lanxin.campus.web;

import com.vivo.lanxin.campus.model.Note;
import com.vivo.lanxin.campus.repository.NoteRepository;
import com.vivo.lanxin.campus.service.AuthService;
import com.vivo.lanxin.campus.service.RagService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/notes")
public class NoteController {
    private final NoteRepository noteRepo;
    private final AuthService auth;
    private final RagService ragService;

    public NoteController(NoteRepository noteRepo, AuthService auth, RagService ragService) {
        this.noteRepo = noteRepo;
        this.auth = auth;
        this.ragService = ragService;
    }

    @GetMapping
    public List<NoteDto> notes(@RequestHeader("Authorization") String authHeader,
                               @RequestParam(required = false) @Size(max = 80) String keyword,
                               @RequestParam(required = false) @Size(max = 200) String folder) {
        long userId = auth.getUserId(authHeader);
        keyword = InputSanitizer.clean(keyword, 80);
        folder = InputSanitizer.clean(folder, 200);
        if (keyword != null && !keyword.isBlank()) {
            return noteRepo.searchByUser(userId, keyword).stream().map(NoteDto::from).toList();
        }
        if (folder != null && !folder.isBlank()) {
            if (folder.endsWith("/")) {
                return noteRepo.findByUserIdAndFolderPathPrefix(userId, folder).stream().map(NoteDto::from).toList();
            }
            return noteRepo.findByUserIdAndFolderPath(userId, folder).stream().map(NoteDto::from).toList();
        }
        return noteRepo.findByUserIdOrderByUpdatedAtDesc(userId).stream().map(NoteDto::from).toList();
    }

    @GetMapping("/folders")
    public List<Map<String, Object>> noteFolders(@RequestHeader("Authorization") String authHeader) {
        long userId = auth.getUserId(authHeader);
        List<String> paths = noteRepo.findDistinctFolderPathsByUserId(userId);
        return ControllerUtils.buildFolderTree(paths);
    }

    @PostMapping
    public NoteDto createNote(@RequestHeader("Authorization") String authHeader,
                              @Valid @RequestBody NoteRequest request) {
        Note note = ControllerUtils.buildNote(request);
        note.setUserId(auth.getUserId(authHeader));
        note = noteRepo.save(note);
        try {
            note.setRagDocumentId(ragService.indexNote(note));
            note = noteRepo.save(note);
        } catch (Exception e) {
            System.err.println("[RAG] Failed to index note " + note.getId() + ": " + e.getMessage());
        }
        return NoteDto.from(note);
    }

    @GetMapping("/{id}")
    public ResponseEntity<NoteDto> note(@RequestHeader("Authorization") String authHeader,
                                         @PathVariable long id) {
        long userId = auth.getUserId(authHeader);
        return noteRepo.findById(id)
                .filter(n -> n.getUserId() == userId)
                .map(note -> ResponseEntity.ok(NoteDto.from(note)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<NoteDto> updateNote(@RequestHeader("Authorization") String authHeader,
                                               @PathVariable long id,
                                               @Valid @RequestBody NoteRequest request) {
        long userId = auth.getUserId(authHeader);
        return noteRepo.findById(id).filter(n -> n.getUserId() == userId).map(note -> {
            note.setTitle(ControllerUtils.first(InputSanitizer.nullable(request.title(), 120), note.getTitle()));
            note.setCourse(ControllerUtils.first(InputSanitizer.nullable(request.course(), 80), note.getCourse()));
            note.setFolderPath(ControllerUtils.first(InputSanitizer.nullable(request.folderPath(), 200), note.getFolderPath()));
            note.setRawText(ControllerUtils.first(InputSanitizer.nullable(request.rawText(), 20_000), note.getRawText()));
            note.setSummary(ControllerUtils.first(InputSanitizer.nullable(request.summary(), 4_000), note.getSummary()));
            if (request.keyPoints() != null) note.setKeyPoints(InputSanitizer.cleanList(request.keyPoints(), 200, 20));
            if (request.formulas() != null) note.setFormulas(InputSanitizer.cleanList(request.formulas(), 200, 20));
            if (request.tags() != null) note.setTags(InputSanitizer.cleanList(request.tags(), 40, 12));
            note.setMindMap(ControllerUtils.first(InputSanitizer.nullable(request.mindMap(), 6_000), note.getMindMap()));
            note = noteRepo.save(note);
            try {
                ragService.reindexNote(note);
                note = noteRepo.save(note);
            } catch (Exception e) {
                System.err.println("[RAG] Failed to reindex note " + note.getId() + ": " + e.getMessage());
            }
            return ResponseEntity.ok(NoteDto.from(note));
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteNote(@RequestHeader("Authorization") String authHeader,
                                            @PathVariable long id) {
        long userId = auth.getUserId(authHeader);
        return noteRepo.findById(id).filter(n -> n.getUserId() == userId).map(note -> {
            if (note.getRagDocumentId() != null) {
                ragService.deleteNoteDocument(note.getRagDocumentId());
            }
            noteRepo.delete(note);
            return ResponseEntity.noContent().<Void>build();
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/mindmap")
    public ResponseEntity<Map<String, String>> noteMindMap(@RequestHeader("Authorization") String authHeader,
                                                            @PathVariable long id) {
        long userId = auth.getUserId(authHeader);
        return noteRepo.findById(id).filter(n -> n.getUserId() == userId)
                .map(note -> ResponseEntity.ok(Map.of("mindMap", note.getMindMap())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/batch-sync")
    public Map<String, Object> batchSync(@RequestHeader("Authorization") String authHeader,
                                          @RequestBody List<NoteRequest> notes) {
        long userId = auth.getUserId(authHeader);
        List<NoteDto> saved = notes.stream().limit(100).map(req -> {
            Note note = ControllerUtils.buildNote(req);
            note.setUserId(userId);
            note = noteRepo.save(note);
            try {
                note.setRagDocumentId(ragService.indexNote(note));
                note = noteRepo.save(note);
            } catch (Exception e) {
                System.err.println("[RAG] Failed to index batch note " + note.getId() + ": " + e.getMessage());
            }
            return NoteDto.from(note);
        }).toList();
        return Map.of("synced", saved.size(), "items", saved);
    }
}
