package com.vivo.lanxin.campus.web;

import com.vivo.lanxin.campus.model.Reminder;
import com.vivo.lanxin.campus.repository.ReminderRepository;
import com.vivo.lanxin.campus.service.AiMockService;
import com.vivo.lanxin.campus.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/reminders")
public class ReminderController {
    private final ReminderRepository reminderRepo;
    private final AuthService auth;
    private final AiMockService ai;

    public ReminderController(ReminderRepository reminderRepo, AuthService auth, AiMockService ai) {
        this.reminderRepo = reminderRepo;
        this.auth = auth;
        this.ai = ai;
    }

    @GetMapping
    public List<ReminderDto> reminders(@RequestHeader("Authorization") String authHeader,
                                       @RequestParam(defaultValue = "false") boolean includeCompleted,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "50") int size) {
        long userId = auth.getUserId(authHeader);
        if (includeCompleted) {
            return reminderRepo.findByUserIdAndCompletedTrueOrderByCompletedAtDesc(userId, PageRequest.of(page, size))
                    .stream().map(ReminderDto::from).toList();
        }
        return reminderRepo.findByUserIdAndCompletedFalse(userId).stream().map(ReminderDto::from).toList();
    }

    @GetMapping("/overdue")
    public Map<String, Object> overdueReminders(@RequestHeader("Authorization") String authHeader) {
        long userId = auth.getUserId(authHeader);
        LocalDate today = LocalDate.now();
        List<ReminderDto> overdue = reminderRepo
                .findByUserIdAndCompletedFalseAndDueDateLessThanOrderByDueDateAsc(userId, today)
                .stream().map(ReminderDto::from).toList();
        long count = reminderRepo.countByUserIdAndCompletedFalseAndDueDateLessThan(userId, today);
        return Map.of("count", count, "items", overdue);
    }

    @PostMapping
    public ReminderDto createReminder(@RequestHeader("Authorization") String authHeader,
                                      @Valid @RequestBody ReminderRequest request) {
        Reminder reminder = ControllerUtils.buildReminder(auth.getUserId(authHeader), request);
        return ReminderDto.from(reminderRepo.save(reminder));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ReminderDto> updateReminder(@RequestHeader("Authorization") String authHeader,
                                                       @PathVariable long id,
                                                       @Valid @RequestBody ReminderRequest request) {
        long userId = auth.getUserId(authHeader);
        return reminderRepo.findById(id).filter(r -> r.getUserId() == userId).map(reminder -> {
            if (request.title() != null) reminder.setTitle(InputSanitizer.clean(request.title(), 120));
            if (request.course() != null) reminder.setCourse(InputSanitizer.nullable(request.course(), 80));
            if (request.dueDate() != null) reminder.setDueDate(request.dueDate());
            if (request.priority() != null) reminder.setPriority(InputSanitizer.nullable(request.priority(), 20));
            if (request.source() != null) reminder.setSource(InputSanitizer.nullable(request.source(), 200));
            if (request.relatedNoteId() != null) reminder.setRelatedNoteId(request.relatedNoteId());
            if (request.recurrence() != null) reminder.setRecurrence(InputSanitizer.nullable(request.recurrence(), 20));
            return ResponseEntity.ok(ReminderDto.from(reminderRepo.save(reminder)));
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}/complete")
    public ResponseEntity<ReminderDto> completeReminder(@RequestHeader("Authorization") String authHeader,
                                                        @PathVariable long id) {
        long userId = auth.getUserId(authHeader);
        return reminderRepo.findById(id).filter(r -> r.getUserId() == userId).map(reminder -> {
            reminder.setCompleted(true);
            reminder.setCompletedAt(LocalDateTime.now());
            return ResponseEntity.ok(ReminderDto.from(reminderRepo.save(reminder)));
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}/uncomplete")
    public ResponseEntity<ReminderDto> uncompleteReminder(@RequestHeader("Authorization") String authHeader,
                                                          @PathVariable long id) {
        long userId = auth.getUserId(authHeader);
        return reminderRepo.findById(id).filter(r -> r.getUserId() == userId).map(reminder -> {
            reminder.setCompleted(false);
            reminder.setCompletedAt(null);
            return ResponseEntity.ok(ReminderDto.from(reminderRepo.save(reminder)));
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteReminder(@RequestHeader("Authorization") String authHeader,
                                                @PathVariable long id) {
        long userId = auth.getUserId(authHeader);
        return reminderRepo.findById(id).filter(r -> r.getUserId() == userId).map(reminder -> {
            reminderRepo.delete(reminder);
            return ResponseEntity.noContent().<Void>build();
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/today")
    public List<ReminderDto> todayReminders(@RequestHeader("Authorization") String authHeader) {
        long userId = auth.getUserId(authHeader);
        LocalDate today = LocalDate.now();
        return reminderRepo.findByUserIdAndCompletedFalseAndDueDateBetweenOrderByDueDateAsc(
                        userId, today, today.plusDays(7))
                .stream().map(ReminderDto::from).toList();
    }

    @GetMapping("/priority")
    public List<ReminderDto> priorityReminders(@RequestHeader("Authorization") String authHeader) {
        long userId = auth.getUserId(authHeader);
        List<String> order = List.of("high", "medium", "low");
        return reminderRepo.findByUserIdAndCompletedFalse(userId).stream()
                .sorted(Comparator.comparingInt(r -> order.indexOf(r.getPriority())))
                .map(ReminderDto::from)
                .toList();
    }

    @PostMapping("/parse")
    public ReminderDto parseReminder(@RequestHeader("Authorization") String authHeader,
                                     @Valid @RequestBody TextRequest request) {
        Reminder reminder = ai.parseReminder(Map.of("text", InputSanitizer.clean(request.text(), 2_000)));
        reminder.setUserId(auth.getUserId(authHeader));
        return ReminderDto.from(reminderRepo.save(reminder));
    }

    @PostMapping("/parse-batch")
    public List<ReminderDto> parseReminders(@RequestHeader("Authorization") String authHeader,
                                            @Valid @RequestBody TextRequest request) {
        long userId = auth.getUserId(authHeader);
        List<Reminder> reminders = ai.parseReminders(InputSanitizer.clean(request.text(), 4_000));
        return reminders.stream().map(r -> {
            r.setUserId(userId);
            return ReminderDto.from(reminderRepo.save(r));
        }).toList();
    }

    @PostMapping("/parse-preview")
    public List<ReminderDto> previewParse(@RequestHeader("Authorization") String authHeader,
                                          @Valid @RequestBody TextRequest request) {
        List<Reminder> reminders = ai.parseReminders(InputSanitizer.clean(request.text(), 4_000));
        return reminders.stream().map(r -> {
            r.setUserId(auth.getUserId(authHeader));
            return ReminderDto.from(r);
        }).toList();
    }

    @PostMapping("/batch-save")
    public List<ReminderDto> batchSave(@RequestHeader("Authorization") String authHeader,
                                        @Valid @RequestBody List<ReminderRequest> requests) {
        long userId = auth.getUserId(authHeader);
        return requests.stream().limit(20)
                .map(req -> ReminderDto.from(reminderRepo.save(ControllerUtils.buildReminder(userId, req))))
                .toList();
    }
}
