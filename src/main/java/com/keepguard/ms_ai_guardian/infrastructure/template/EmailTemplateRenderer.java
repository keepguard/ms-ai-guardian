package com.keepguard.ms_ai_guardian.infrastructure.template;

import com.keepguard.ms_ai_guardian.application.port.out.notification.NotificationKind;
import com.keepguard.ms_ai_guardian.infrastructure.config.GuardianProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class EmailTemplateRenderer {

    private final ClasspathResourceLoader classpath;
    private final GuardianProperties properties;

    public String render(NotificationKind kind, Map<String, String> variables) {
        Map<String, String> vars = new HashMap<>();
        if (variables != null) {
            vars.putAll(variables);
        }
        vars.putIfAbsent("approverDisplayName", properties.getApproverDisplayName());
        vars.putIfAbsent("approverGithub", properties.getApproverGithub());
        vars.putIfAbsent("namespace", properties.getNamespace());
        vars.putIfAbsent("headerColor", vars.getOrDefault("headerColor", "#0f766e"));
        String styles = PlaceholderRenderer.render(classpath.load("templates/email/_styles.css"), vars);
        vars.put("styles", styles);
        String template = classpath.load("templates/email/" + kind.templateName() + ".html");
        return PlaceholderRenderer.render(template, vars);
    }
}
