package com.hys.classcord.auth.repository;

import com.hys.classcord.auth.entity.User;
import com.hys.classcord.auth.entity.UserIdentity;
import com.hys.classcord.auth.enums.AuthProvider;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserIdentityRepository extends JpaRepository<UserIdentity, UUID> {

    Optional<UserIdentity> findByUserAndProvider(User user, AuthProvider provider);

    // 判定該第三方用戶是否曾經登入過
    Optional<UserIdentity> findByProviderAndProviderUid(String provider, String providerUid);
}
