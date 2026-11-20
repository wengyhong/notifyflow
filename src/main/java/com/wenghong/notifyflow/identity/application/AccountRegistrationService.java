package com.wenghong.notifyflow.identity.application;

import java.time.Clock;
import java.time.Instant;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wenghong.notifyflow.identity.domain.AppUser;
import com.wenghong.notifyflow.identity.domain.Tenant;
import com.wenghong.notifyflow.identity.domain.TenantMembership;
import com.wenghong.notifyflow.identity.domain.TenantRole;
import com.wenghong.notifyflow.identity.persistence.AppUserRepository;
import com.wenghong.notifyflow.identity.persistence.TenantMembershipRepository;
import com.wenghong.notifyflow.identity.persistence.TenantRepository;

import org.hibernate.exception.ConstraintViolationException;
@Service
public class AccountRegistrationService {

    private final AppUserRepository users;
    private final TenantRepository tenants;
    private final TenantMembershipRepository memberships;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public AccountRegistrationService(AppUserRepository users, TenantRepository tenants,
            TenantMembershipRepository memberships, PasswordEncoder passwordEncoder, Clock clock) {
        this.users = users;
        this.tenants = tenants;
        this.memberships = memberships;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Transactional
    public AccountRegistrationResult register(String email, String password, String tenantName)
    {

        if(!RegistrationPasswordPolicy.isValid(password))
        {
            throw new InvalidRegistrationPasswordException();
        }

        Instant now = clock.instant();
        String passwordHash = passwordEncoder.encode(password);

        AppUser user;

        try{
            user = users.saveAndFlush(new AppUser(email, passwordHash, now));
        }
        catch(DataIntegrityViolationException error)
        {
            if(isEmailUniqueViolation(error))
            {
                throw new EmailAlreadyRegisteredException(error);
            }

            throw error;
        }

        Tenant tenant = tenants.save(new Tenant(tenantName, now));

        memberships.save(new TenantMembership(tenant, user, TenantRole.ADMIN, now));

        return new AccountRegistrationResult(user.getId(), tenant.getId());
    }


    private boolean isEmailUniqueViolation(Throwable error) {
    Throwable current = error;

    while (current != null) {
        if (current instanceof ConstraintViolationException violation
                && "uq_app_users_email_lower"
                        .equals(violation.getConstraintName())) {
            return true;
        }

        current = current.getCause();
    }

    return false;
}


}
