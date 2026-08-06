package com.electrahub.aisupport.service;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class AdminMutationPlanner {
    private static final Pattern CONFIRM = Pattern.compile("^\\s*confirm\\s+([0-9a-fA-F-]{36})\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern STOP_SESSION = Pattern.compile(
            "\\bstop(?:\\s+charging)?\\s+session\\s+([0-9a-fA-F-]{36})\\b", Pattern.CASE_INSENSITIVE);

    public Optional<MutationCommand> parse(String message) {
        String value = message == null ? "" : message.trim();
        Matcher confirmation = CONFIRM.matcher(value);
        if (confirmation.matches()) {
            return parseUuid(confirmation.group(1)).map(MutationCommand::confirm);
        }
        Matcher stop = STOP_SESSION.matcher(value.toLowerCase(Locale.ROOT));
        if (stop.find()) {
            return parseUuid(stop.group(1)).map(id -> MutationCommand.propose(Operation.STOP_SESSION, id));
        }
        return Optional.empty();
    }

    private static Optional<UUID> parseUuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    public enum Operation { STOP_SESSION }

    public record MutationCommand(Operation operation, UUID targetId, UUID confirmationId) {
        static MutationCommand propose(Operation operation, UUID targetId) {
            return new MutationCommand(operation, targetId, null);
        }

        static MutationCommand confirm(UUID confirmationId) {
            return new MutationCommand(null, null, confirmationId);
        }

        boolean confirmation() {
            return confirmationId != null;
        }
    }
}
