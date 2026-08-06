package com.electrahub.aisupport.service;

import com.electrahub.aisupport.security.TrustedIdentityContextResolver.IdentityContext;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class TenantAiQuotaService {
    private static final DefaultRedisScript<Long> RESERVE = new DefaultRedisScript<>("""
            local minute = tonumber(redis.call('GET', KEYS[1]) or '0')
            local daily = tonumber(redis.call('GET', KEYS[2]) or '0')
            local tokens = tonumber(redis.call('GET', KEYS[3]) or '0')
            if minute >= tonumber(ARGV[1]) then return 1 end
            if daily >= tonumber(ARGV[2]) then return 2 end
            if tokens + tonumber(ARGV[4]) > tonumber(ARGV[3]) then return 3 end
            minute = redis.call('INCR', KEYS[1])
            daily = redis.call('INCR', KEYS[2])
            tokens = redis.call('INCRBY', KEYS[3], tonumber(ARGV[4]))
            if minute == 1 then redis.call('EXPIRE', KEYS[1], tonumber(ARGV[5])) end
            if daily == 1 then redis.call('EXPIRE', KEYS[2], tonumber(ARGV[6])) end
            if tokens == tonumber(ARGV[4]) then redis.call('EXPIRE', KEYS[3], tonumber(ARGV[6])) end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final TenantAiPolicyService policies;
    private final AiAuditService audit;
    private final Clock clock;

    @Autowired
    public TenantAiQuotaService(StringRedisTemplate redis, TenantAiPolicyService policies, AiAuditService audit) {
        this(redis, policies, audit, Clock.systemUTC());
    }

    TenantAiQuotaService(StringRedisTemplate redis, TenantAiPolicyService policies, AiAuditService audit, Clock clock) {
        this.redis = redis;
        this.policies = policies;
        this.audit = audit;
        this.clock = clock;
    }

    public TenantAiPolicyService.TenantPolicy admit(IdentityContext identity, String userMessage) {
        TenantAiPolicyService.TenantPolicy policy = policies.policyFor(identity == null ? null : identity.tenantId());
        if (!policy.enabled()) {
            audit.quotaDecision(identity, "tenant-disabled");
            throw new TenantAiAccessException("AI is not enabled for this tenant");
        }
        if (!policies.quotaEnabled()) {
            return policy;
        }

        Instant now = clock.instant();
        String hashTag = "{" + policy.tenantId() + "}";
        String minute = DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneOffset.UTC).format(now);
        String day = DateTimeFormatter.BASIC_ISO_DATE.withZone(ZoneOffset.UTC).format(now);
        int reservedTokens = estimatedTokens(userMessage);
        try {
            Long outcome = redis.execute(RESERVE,
                    List.of("ai:quota:" + hashTag + ":minute:" + minute,
                            "ai:quota:" + hashTag + ":day:" + day,
                            "ai:quota:" + hashTag + ":tokens:" + day),
                    Integer.toString(policy.requestsPerMinute()), Integer.toString(policy.requestsPerDay()),
                    Integer.toString(policy.tokensPerDay()), Integer.toString(reservedTokens), "120", "172800");
            if (outcome != null && outcome == 0L) {
                audit.quotaDecision(identity, "allowed");
                return policy;
            }
            String reason = outcome != null && outcome == 1L ? "minute-limit"
                    : outcome != null && outcome == 3L ? "token-limit" : "daily-limit";
            audit.quotaDecision(identity, reason);
            throw new TenantAiQuotaException("This tenant has reached its AI request limit. Please try again later.");
        } catch (TenantAiQuotaException ex) {
            throw ex;
        } catch (RedisConnectionFailureException ex) {
            return unavailable(identity, policy, ex);
        } catch (RuntimeException ex) {
            return unavailable(identity, policy, ex);
        }
    }

    static int estimatedTokens(String userMessage) {
        int inputCharacters = userMessage == null ? 0 : userMessage.length();
        return Math.max(1, (inputCharacters + 3) / 4) + 180;
    }

    private TenantAiPolicyService.TenantPolicy unavailable(IdentityContext identity,
                                                            TenantAiPolicyService.TenantPolicy policy,
                                                            RuntimeException ex) {
        audit.quotaDecision(identity, policies.quotaFailClosed() ? "unavailable-denied" : "unavailable-allowed");
        if (policies.quotaFailClosed()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "AI usage controls are temporarily unavailable", ex);
        }
        return policy;
    }
}
