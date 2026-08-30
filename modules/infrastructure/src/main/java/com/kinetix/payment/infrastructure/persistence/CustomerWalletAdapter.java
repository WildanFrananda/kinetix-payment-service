package com.kinetix.payment.infrastructure.persistence;

import com.kinetix.payment.domain.entity.CustomerWallet;
import com.kinetix.payment.domain.port.CustomerWalletRepositoryPort;
import org.springframework.stereotype.Component;
import java.util.Optional;

@Component
public class CustomerWalletAdapter implements CustomerWalletRepositoryPort {
    private final CustomerWalletJpaRepository jpaRepository;

    public CustomerWalletAdapter(CustomerWalletJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Optional<CustomerWallet> findByCustomerId(Long customerId) {
        return jpaRepository.findByCustomerId(customerId)
            .map(this::toDomain);
    }

    @Override
    public CustomerWallet save(CustomerWallet wallet) {
        CustomerWalletJpaEntity entity = new CustomerWalletJpaEntity(
            wallet.id(),
            wallet.customerId(),
            wallet.balance(),
            wallet.heldBalance(),
            wallet.currency(),
            wallet.createdAt(),
            wallet.updatedAt()
        );
        CustomerWalletJpaEntity saved = jpaRepository.save(entity);
        return toDomain(saved);
    }

    private CustomerWallet toDomain(CustomerWalletJpaEntity entity) {
        return new CustomerWallet(
            entity.getId(),
            entity.getCustomerId(),
            entity.getBalance(),
            entity.getHeldBalance(),
            entity.getCurrency(),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }
}
