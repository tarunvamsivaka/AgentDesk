package com.agentdesk.core.persistence.converter

import androidx.room.TypeConverter
import com.agentdesk.core.common.model.*

class AppConverters {

    @TypeConverter
    fun fromDeviceTier(value: DeviceTier): String = value.name

    @TypeConverter
    fun toDeviceTier(value: String): DeviceTier = DeviceTier.valueOf(value)

    @TypeConverter
    fun fromRiskLevel(value: RiskLevel): String = value.name

    @TypeConverter
    fun toRiskLevel(value: String): RiskLevel = RiskLevel.valueOf(value)

    @TypeConverter
    fun fromConsentState(value: ConsentState): String = value.name

    @TypeConverter
    fun toConsentState(value: String): ConsentState = ConsentState.valueOf(value)

    @TypeConverter
    fun fromCommandSource(value: CommandSource): String = value.name

    @TypeConverter
    fun toCommandSource(value: String): CommandSource = CommandSource.valueOf(value)

    @TypeConverter
    fun fromTaskStatus(value: TaskStatus): String = value.name

    @TypeConverter
    fun toTaskStatus(value: String): TaskStatus = TaskStatus.valueOf(value)

    @TypeConverter
    fun fromStepStatus(value: StepStatus): String = value.name

    @TypeConverter
    fun toStepStatus(value: String): StepStatus = StepStatus.valueOf(value)

    @TypeConverter
    fun fromIndexStatus(value: IndexStatus): String = value.name

    @TypeConverter
    fun toIndexStatus(value: String): IndexStatus = IndexStatus.valueOf(value)

    @TypeConverter
    fun fromKnowledgeSourceType(value: KnowledgeSourceType): String = value.name

    @TypeConverter
    fun toKnowledgeSourceType(value: String): KnowledgeSourceType =
        KnowledgeSourceType.valueOf(value)
}
