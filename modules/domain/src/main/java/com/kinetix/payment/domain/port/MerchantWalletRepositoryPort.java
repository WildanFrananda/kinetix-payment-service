package com.kinetix.payment.domain.port;

import com.kinetix.payment.domain.entity.MerchantWallet;
import java.util.Optional;

public interface MerchantWalletRepositoryPort {
    Optional<MerchantWallet> findByMerchantId(Long merchantId);
    MerchantWallet save(MerchantWallet wallet);
}
