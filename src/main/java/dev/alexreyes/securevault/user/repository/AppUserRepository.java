package dev.alexreyes.securevault.user.repository;

import dev.alexreyes.securevault.user.entity.AppUser;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    Optional<AppUser> findByEmail(String email);

    boolean existsByEmail(String email);

    /**
     * Loads the user with a row lock (SELECT ... FOR UPDATE) held until the
     * surrounding transaction ends. Used to serialize one user's uploads so
     * the storage quota check and the insert are atomic.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from AppUser u where u.id = :id")
    Optional<AppUser> findByIdForUpdate(UUID id);
}
