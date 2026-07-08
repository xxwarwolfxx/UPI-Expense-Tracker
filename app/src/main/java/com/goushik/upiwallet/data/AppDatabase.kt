package com.goushik.upiwallet.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        TransactionEntity::class,
        RawEventEntity::class,
        BalanceAnchorEntity::class,
        UserProfileEntity::class,
        MerchantRuleEntity::class,
        BudgetEntity::class,
    ],
    version = 7,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun rawEventDao(): RawEventDao
    abstract fun balanceAnchorDao(): BalanceAnchorDao
    abstract fun userProfileDao(): UserProfileDao
    abstract fun merchantRuleDao(): MerchantRuleDao
    abstract fun budgetDao(): BudgetDao
}
