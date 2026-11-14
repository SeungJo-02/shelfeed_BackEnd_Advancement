package com.shelfeed.backend.global.cache;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 캐시 무효화를 커밋 이후로 미룬다.
 *
 * <p>트랜잭션 안에서 바로 지우면, 지운 직후 커밋 전에 들어온 읽기가 아직 옛 값인 DB를 보고
 * 그 옛 값을 다시 캐시에 채운다. 커밋이 끝난 뒤에 지워야 그 틈이 없다.
 * 트랜잭션이 없으면(스케줄러 등) 그 자리에서 바로 지운다.
 */
public final class AfterCommit {

    private AfterCommit() {
    }

    public static void run(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
