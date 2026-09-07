package com.keepguard.ms_ai_guardian.application.port.out.auth;

import java.util.Optional;
import java.util.UUID;

public interface AuthTokenPort {

    Optional<String> getToken(UUID companyId);
}
