package com.boongboongus.backend.domain.home;

import com.boongboongus.backend.domain.auth.repository.AccountRepository;
import com.boongboongus.backend.domain.couple.service.CoupleService;
import com.boongboongus.backend.domain.notification.service.NotificationService;
import com.boongboongus.backend.domain.card.CardStore;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Fallback;
import org.springframework.stereotype.Component;

@Component
@Fallback
class AccountHomeDataSource implements HomeDataSource {
    private final AccountRepository accounts;
    private final CoupleService couples;
    private final NotificationService notifications;
    private final CardStore cards;

    AccountHomeDataSource(AccountRepository accounts, CoupleService couples, NotificationService notifications, CardStore cards) {
        this.accounts = accounts;
        this.couples = couples;
        this.notifications = notifications;
        this.cards = cards;
    }

    @Override
    public Optional<Summary> findForUser(String userId) {
        return accounts.findById(userId).map(account -> {
            var me = new User(account.id(), account.nickname(), account.birthDate(), account.profileImageUrl(),
                    account.provider(), account.onboardingCompleted(), account.gender());
            var relation = couples.getCurrent(userId).couple();
            Couple couple = relation == null ? null : new Couple(relation.id(), relation.status().name(),
                    relation.members().stream().map(member -> new Member(member.id(), member.nickname(),
                            member.birthDate(), member.profileImageUrl(), member.provider())).toList(),
                    relation.startDate(), Math.toIntExact(relation.daysTogether()), relation.cumulativeDistanceMeters(),
                    Math.toIntExact(relation.completedCardCount()), relation.version(), relation.mascotName());
            List<PendingCard> pending = couple == null ? List.of() : cards.pending(couple.id(), userId);
            return new Summary(me, couple, notifications.countUnread(userId), pending);
        });
    }
}
