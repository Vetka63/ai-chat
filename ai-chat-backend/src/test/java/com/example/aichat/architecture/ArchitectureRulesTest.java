package com.example.aichat.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class ArchitectureRulesTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java");
    private static final Path PACKAGE_ROOT = SOURCE_ROOT.resolve("com/example/aichat");
    private static final Pattern PACKAGE_PATTERN = Pattern.compile("package\\s+([^;]+);");
    private static final Pattern TYPE_DECLARATION = Pattern.compile(
            "(?m)^(?:public\\s+)?(?:final\\s+)?(?:class|interface|record|enum)\\s+"
    );
    private static final Pattern INTERNAL_MODULE_IMPORT = Pattern.compile(
            "import\\s+com\\.example\\.aichat\\.([a-z][a-z0-9_]*)(?:\\.|;)"
    );
    private static final Pattern CONTROLLER_DEPENDENCY = Pattern.compile(
            "import\\s+com\\.example\\.aichat\\..*\\.controller(?:\\.|;)"
    );
    private static final List<String> TOP_LEVEL_MODULES = List.of(
            "bootstrap", "common", "config", "task"
    );

    @Test
    void javaPackagesMatchTheirDirectories() throws IOException {
        for (var source : javaSources()) {
            var content = Files.readString(source);
            var matcher = PACKAGE_PATTERN.matcher(content);
            assertThat(matcher.find()).as("package declaration in %s", source).isTrue();
            var expectedPackage = SOURCE_ROOT.relativize(source.getParent())
                    .toString().replace('\\', '.').replace('/', '.');
            assertThat(matcher.group(1)).as("package path for %s", source)
                    .isEqualTo(expectedPackage);
        }
    }

    @Test
    void nonControllerCodeDoesNotImportControllerDtos() throws IOException {
        for (var source : javaSources()) {
            var path = source.toString().replace('\\', '/');
            if (path.contains("/controller/")) {
                continue;
            }
            assertThat(CONTROLLER_DEPENDENCY.matcher(Files.readString(source)).find())
                    .as("non-controller source must not depend on HTTP layer: %s", source)
                    .isFalse();
        }
    }

    @Test
    void llmPortIsIndependentFromProfilesAndTasks() throws IOException {
        var contract = Files.readString(PACKAGE_ROOT.resolve("common/llm/LlmClient.java"));
        assertThat(contract)
                .doesNotContain("profile.", "task.", "AgentProfile", "ResponseModeConfig")
                .contains("LlmCompletionRequest");
    }

    @Test
    void commonProfileDoesNotKnowSpecificTaskConfiguration() throws IOException {
        var profile = Files.readString(PACKAGE_ROOT.resolve("common/profile/model/AgentProfile.java"));
        assertThat(profile)
                .doesNotContain("task.", "ReasoningExperimentConfig", "JudgeConfig")
                .contains("featureConfig");
    }

    @Test
    void commonDoesNotDependOnTaskCode() throws IOException {
        for (var source : javaSourcesUnder("common")) {
            assertThat(Files.readString(source))
                    .as("common extension point must not depend on a task: %s", source)
                    .doesNotContain("com.example.aichat.task");
        }
    }

    @Test
    void taskDomainsDoNotDependOnOtherTaskDomains() throws IOException {
        for (var source : javaSourcesUnder("task")) {
            var relative = PACKAGE_ROOT.resolve("task").relativize(source);
            if (relative.getNameCount() < 2) {
                continue;
            }
            var owner = relative.getName(0).toString();
            var content = Files.readString(source);
            var matcher = Pattern.compile("import\\s+com\\.example\\.aichat\\.task\\.([a-z][a-z0-9]*)")
                    .matcher(content);
            while (matcher.find()) {
                assertThat(matcher.group(1))
                        .as("task domain %s must not depend on task domain %s in %s",
                                owner, matcher.group(1), source)
                        .isEqualTo(owner);
            }
        }
    }

    @Test
    void topLevelModuleDependenciesAreAcyclic() throws IOException {
        Map<String, Set<String>> graph = new LinkedHashMap<>();
        TOP_LEVEL_MODULES.forEach(module -> graph.put(module, new LinkedHashSet<>()));
        for (var source : javaSources()) {
            var relative = PACKAGE_ROOT.relativize(source);
            if (relative.getNameCount() < 2) {
                continue;
            }
            var owner = relative.getName(0).toString();
            if (!graph.containsKey(owner)) {
                continue;
            }
            var matcher = INTERNAL_MODULE_IMPORT.matcher(Files.readString(source));
            while (matcher.find()) {
                var dependency = matcher.group(1);
                if (graph.containsKey(dependency) && !owner.equals(dependency)) {
                    graph.get(owner).add(dependency);
                }
            }
        }
        var cycle = findCycle(graph);
        assertThat(cycle).as("top-level module cycle: %s", String.join(" -> ", cycle)).isEmpty();
    }

    @Test
    void productionCodeDoesNotDependOnBootstrap() throws IOException {
        for (var source : javaSources()) {
            var relative = PACKAGE_ROOT.relativize(source);
            if (relative.getNameCount() < 2 || "bootstrap".equals(relative.getName(0).toString())) {
                continue;
            }
            assertThat(Files.readString(source)).as("module must not depend on bootstrap: %s", source)
                    .doesNotContain("com.example.aichat.bootstrap");
        }
    }

    @Test
    void legacyLayerPackagesContainNoJavaSources() throws IOException {
        var legacyRoots = List.of("agent", "api", "challenge", "chat", "enums", "experiment",
                "input", "llm", "output", "service", "shared", "validation");
        assertThat(javaSources()).noneMatch(source -> legacyRoots.stream()
                .map(PACKAGE_ROOT::resolve).anyMatch(source::startsWith));
    }

    @Test
    void legacyTaskPackagesContainNoJavaSources() throws IOException {
        var legacyRoots = List.of(
                PACKAGE_ROOT.resolve("task/recipe"),
                PACKAGE_ROOT.resolve("task/day2/responseformat")
        );
        assertThat(javaSources())
                .noneMatch(source -> legacyRoots.stream().anyMatch(source::startsWith));
    }

    @Test
    void everyDomainHasNearbyDocumentation() {
        assertThat(List.of(
                PACKAGE_ROOT.resolve("bootstrap/README.md"),
                PACKAGE_ROOT.resolve("common/README.md"),
                PACKAGE_ROOT.resolve("common/profile/README.md"),
                PACKAGE_ROOT.resolve("common/inputpolicy/README.md"),
                PACKAGE_ROOT.resolve("common/outputpolicy/README.md"),
                PACKAGE_ROOT.resolve("common/validator/README.md"),
                PACKAGE_ROOT.resolve("common/llm/README.md"),
                PACKAGE_ROOT.resolve("common/llmjudge/README.md"),
                PACKAGE_ROOT.resolve("task/README.md"),
                PACKAGE_ROOT.resolve("task/chat/README.md"),
                PACKAGE_ROOT.resolve("task/day2/README.md"),
                PACKAGE_ROOT.resolve("task/day2/recipe/README.md"),
                PACKAGE_ROOT.resolve("task/day2/answer/README.md"),
                PACKAGE_ROOT.resolve("task/day3/reasoning/README.md"),
                PACKAGE_ROOT.resolve("task/day3/reasoning/llmjudge/README.md"),
                PACKAGE_ROOT.resolve("task/day4/temperature/README.md"),
                PACKAGE_ROOT.resolve("task/day4/temperature/llmjudge/README.md"),
                PACKAGE_ROOT.resolve("task/day5/modelcomparison/README.md"),
                PACKAGE_ROOT.resolve("task/day5/modelcomparison/llmjudge/README.md"),
                Path.of("src/main/resources/agents/README.md")
        )).allMatch(Files::isRegularFile);
    }

    @Test
    void everyProductionTypeHasJavadoc() throws IOException {
        for (var source : javaSources()) {
            var content = Files.readString(source);
            var declaration = TYPE_DECLARATION.matcher(content);
            assertThat(declaration.find()).as("top-level type declaration in %s", source).isTrue();
            var javadocIndex = content.lastIndexOf("/**", declaration.start());
            var lastImportIndex = content.lastIndexOf("import ", declaration.start());
            assertThat(javadocIndex)
                    .as("production type must explain its responsibility after imports: %s", source)
                    .isGreaterThan(lastImportIndex);
        }
    }

    private static List<String> findCycle(Map<String, Set<String>> graph) {
        Set<String> visited = new LinkedHashSet<>();
        List<String> activePath = new ArrayList<>();
        for (var module : graph.keySet()) {
            var cycle = findCycle(module, graph, visited, activePath);
            if (!cycle.isEmpty()) {
                return cycle;
            }
        }
        return List.of();
    }

    private static List<String> findCycle(
            String module, Map<String, Set<String>> graph,
            Set<String> visited, List<String> activePath
    ) {
        var cycleStart = activePath.indexOf(module);
        if (cycleStart >= 0) {
            List<String> cycle = new ArrayList<>(activePath.subList(cycleStart, activePath.size()));
            cycle.add(module);
            return cycle;
        }
        if (visited.contains(module)) {
            return List.of();
        }
        activePath.add(module);
        for (var dependency : graph.getOrDefault(module, Set.of())) {
            var cycle = findCycle(dependency, graph, visited, activePath);
            if (!cycle.isEmpty()) {
                return cycle;
            }
        }
        activePath.remove(activePath.size() - 1);
        visited.add(module);
        return List.of();
    }

    private static List<Path> javaSourcesUnder(String module) throws IOException {
        try (var files = Files.walk(PACKAGE_ROOT.resolve(module))) {
            return files.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }

    private static List<Path> javaSources() throws IOException {
        try (var files = Files.walk(PACKAGE_ROOT)) {
            return files.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }
}
