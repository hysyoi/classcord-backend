package com.hys.classcord.auth.repository;

import com.hys.classcord.auth.entity.User;
import com.hys.classcord.auth.entity.UserIdentity;
import com.hys.classcord.auth.enums.AuthProvider;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserIdentityRepository extends JpaRepository<UserIdentity, UUID> {

    Optional<UserIdentity> findByUserAndProvider(User user, AuthProvider provider);

    // 取得該使用者綁定的所有登入方式（本地帳密、Google、GitHub、Discord...）
    List<UserIdentity> findByUser(User user);

    // 判定該第三方用戶是否曾經登入過
    Optional<UserIdentity> findByProviderAndProviderUid(AuthProvider provider, String providerUid);
}
