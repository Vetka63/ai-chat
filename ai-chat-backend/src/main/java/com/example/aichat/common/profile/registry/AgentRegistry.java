package com.example.aichat.common.profile.registry;

import com.example.aichat.common.profile.model.AgentProfile;
import com.example.aichat.config.ChatProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Загружает YAML-профили агентов, проверяет их и индексирует по идентификатору. */
@Component
public class AgentRegistry {

    private final Map<String, AgentProfile> profiles;

    public AgentRegistry(
            ResourcePatternResolver resourcePatternResolver,
            ChatProperties properties
    ) {
        var mapper = new ObjectMapper(new YAMLFactory())
                .findAndRegisterModules()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.profiles = loadProfiles(
                resourcePatternResolver,
                mapper,
                properties.agentProfilesPattern()
        );
        if (!profiles.containsKey(properties.defaultAgentId())) {
            throw new IllegalStateException(
                    "Default agent profile does not exist: " + properties.defaultAgentId()
            );
        }
    }

    private static Map<String, AgentProfile> loadProfiles(
            ResourcePatternResolver resolver,
            ObjectMapper mapper,
            String pattern
    ) {
        try {
            var resources = resolver.getResources(pattern);
            Arrays.sort(resources, (left, right) ->
                    filename(left).compareTo(filename(right)));

            Map<String, AgentProfile> loaded = new LinkedHashMap<>();
            for (var resource : resources) {
                var profile = readProfile(mapper, resource);
                if (!profile.enabled()) {
                    continue;
                }
                if (loaded.putIfAbsent(profile.id(), profile) != null) {
                    throw new IllegalStateException(
                            "Duplicate agent profile id: " + profile.id()
                    );
                }
            }
            if (loaded.isEmpty()) {
                throw new IllegalStateException(
                        "No enabled agent profiles found: " + pattern
                );
            }
            return Collections.unmodifiableMap(new LinkedHashMap<>(loaded));
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Failed to load agent profiles: " + pattern,
                    exception
            );
        }
    }

    private static AgentProfile readProfile(
            ObjectMapper mapper,
            Resource resource
    ) throws IOException {
        try (var inputStream = resource.getInputStream()) {
            return mapper.readValue(inputStream, AgentProfile.class);
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "Invalid agent profile: " + filename(resource),
                    exception
            );
        }
    }

    private static String filename(Resource resource) {
        return resource.getFilename() == null ? resource.getDescription() : resource.getFilename();
    }

    public AgentProfile get(String id) {
        var profile = profiles.get(id);
        if (profile == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Unknown chat profile: " + id
            );
        }
        return profile;
    }

    public List<AgentProfile> all() {
        return List.copyOf(profiles.values());
    }
}
