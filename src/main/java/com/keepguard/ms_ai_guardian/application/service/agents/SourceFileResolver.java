package com.keepguard.ms_ai_guardian.application.service.agents;

import com.keepguard.ms_ai_guardian.adapters.out.github.GitHubApiClient;
import com.keepguard.ms_ai_guardian.infrastructure.util.IncidentSourceLocator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SourceFileResolver {

    private final GitHubApiClient gitHubClient;

    public record ResolvedFile(String path, String content, String sha, Integer lineNumber) {}

    public Optional<ResolvedFile> resolve(String repoName, String branch, String logs, String errorReason) {
        var hint = IncidentSourceLocator.parse(logs, errorReason);
        List<String> tree = gitHubClient.listSourceFilePaths(repoName, branch);
        List<String> ranked = new ArrayList<>(IncidentSourceLocator.rankPaths(tree, hint, errorReason));
        if (ranked.isEmpty() && !hint.basenames().isEmpty()) {
            for (String path : tree) {
                String base = path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1);
                if (hint.basenames().stream().anyMatch(base::equalsIgnoreCase)) {
                    ranked.add(path);
                }
            }
        }

        for (String path : ranked) {
            Map<String, String> file = gitHubClient.getFileContent(repoName, path, branch);
            if (file.containsKey("content") && !file.get("content").isBlank()) {
                log.info("📂 [SourceFileResolver] Arquivo do incidente: {} (linha {})", path, hint.lineNumber());
                return Optional.of(new ResolvedFile(path, file.get("content"), file.get("sha"), hint.lineNumber()));
            }
        }

        log.warn("📂 [SourceFileResolver] Nenhum arquivo de código encontrado para {} a partir dos logs.", repoName);
        return Optional.empty();
    }
}
