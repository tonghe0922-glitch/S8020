package cn.shangjingu.platform.iam.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

class RedisSessionStoreTest {
    @Test
    void convertsRedisInfrastructureFailuresToAnExplicitSessionReason() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenThrow(new RedisConnectionFailureException("redis down"));
        RedisSessionStore store = new RedisSessionStore(redis);

        assertThatThrownBy(() -> store.findByAccessDigest("digest"))
                .isInstanceOfSatisfying(SessionRejectedException.class, exception -> {
                    assertThat(exception.reason())
                            .isEqualTo(SessionRejectedException.Reason.STORE_UNAVAILABLE);
                    assertThat(exception.getCause())
                            .isInstanceOf(RedisConnectionFailureException.class);
                });
    }
}
