package com.mparticle.lints.detectors

import com.android.tools.lint.detector.api.*
import com.mparticle.MPEvent
import com.mparticle.MParticle
import com.mparticle.commerce.CommerceEvent
import com.mparticle.internal.Logger
import com.mparticle.lints.basedetectors.CallScanner
import com.mparticle.lints.dtos.Expression
import com.mparticle.tooling.*
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.json.JSONObject
import java.io.File
import com.mparticle.tooling.Utils.executeCLI


class DataplanDetector: CallScanner() {

    private var dataplanningNode: DataPlanningNodeApp? = null
    private var dataplan: String? = null

    companion object {

        val ISSUE = Issue.create(
                "DataplanViolation",
                "field conflicts with dataplan dataplanConstaints",
                "this field is in violation of constrains defined in your organization or workspace's dataplan",
                Category.CORRECTNESS,
                10,
                Severity.ERROR,
                Implementation(DataplanDetector::class.java, Scope.JAVA_FILE_SCOPE)
        )

        val NODE_MISSING = Issue.create(
                "NodeMissing",
                "Unable to validate Dataplan, Node is not present",
                "The MParticle Dataplan validation library required the Node cli tools to be installed. to insure you have Node installed, run \"node -v\" from the Command Line and insure your output is not \"command not found\"",
                Category.USABILITY,
                4,
                Severity.INFORMATIONAL,
                Implementation(DataplanDetector::class.java, Scope.JAVA_FILE_SCOPE)
        )

        val NO_DATA_PLAN = Issue.create(
                "DataPlanMissing",
                "Unable to fetch Data Plan, please check credentials",
                "Retreiving the MParticle Data Plan is necessary to evaluate any violations. There may be a problem with your \"accountId\" or \"dataPlanId\" set in your credentials. Please double check the values are present and correct in you \"mparticle\" block in build.gradle or you mparticle-config.json file",
                Category.USABILITY,
                4,
                Severity.INFORMATIONAL,
                Implementation(DataplanDetector::class.java, Scope.JAVA_FILE_SCOPE)
        )
    }

    override fun getApplicableClasses() = listOf(MPEvent.Builder::class.java, CommerceEvent.Builder::class.java)

    override fun beforeCheckRootProject(context: Context) {
        super.beforeCheckRootProject(context)

        //stub logger in MParticle to avoid any dependencies on Android Log when building Events
        Logger.setLogHandler(object : Logger.AbstractLogHandler() {
            override fun log(priority: MParticle.LogLevel?, error: Throwable?, messages: String?) {}
        })


        if (config != null) {
            dataplanningNode = DataPlanningNodeApp(config!!)
            dataplan = try { Utils.getLocalDataplan() } catch (e: Exception) {
                disabled = true
                return
            }
            if (config?.resultsFile?.isEmpty() == false) {
                File(config!!.resultsFile!!).apply {
                    if (exists()) {
                        delete()
                    }
                    createNewFile()
                }
            }
        } else {
            disabled = true
        }
    }

    override fun onInstanceCollected(context: JavaContext, unresolvedObject: Expression, reportingNode: UExpression) {
        val instance = try { unresolvedObject.resolve() } catch (e: Exception) { }
        //this will make MParticle not break incase Logger happens to be called
        val message = when (instance) {
            is MPEvent.Builder -> instance.build().message
            is CommerceEvent.Builder -> instance.build().message
            is CommerceEvent -> instance.message
            is MPEvent -> instance.message
            else -> null
        }

        if (message == null) {
            return
        }

        if (config?.debugReportServerMessage == true) {
            context.report(
                    ISSUE,
                    reportingNode,
                    context.getLocation(reportingNode),
                    message.toString(4)
            )
            return
        }

        val dp = dataplan
        if (dp == null) {
            context.report(
                    NO_DATA_PLAN,
                    reportingNode,
                    context.getLocation(reportingNode),
                    "Data Plan missing, ${config!!.dataPlanFile} from ${File(".").path}"
            )
        } else if (dataplanningNode?.checkMPInstalled() == false) {
            context.report(
                    NODE_MISSING,
                    reportingNode,
                    context.getLocation(reportingNode),
                    "MParticle CLI tools missing, run \"./gradlew mpInstall\""
            )
        } else {
            var messageString = message.attributesToNumbers().toString()
            val result: DataPlanningNodeApp.NodeAppResult<List<ValidationResult>>? = dataplanningNode?.validate(dp, messageString, config?.dataPlanVersion)
            if (result?.response?.size ?: 0 > 0) {
                result?.response?.forEach { validationResult ->
                    validationResult.data?.validationErrors?.forEach { error ->
                        var errorMessage =
                                when (error.errorPointer) {
                                    "#/data/custom_attributes", "#/data/custom_attributes/${error.key}" -> getErrorMessageBySchemaKeyword(ViolationSchemaKeywordType.get(error.schemaKeyworkd), error.expected)
                                    "#" -> when (validationResult.data?.match?.type) {
                                        "commerce_event" -> "Unplanned Commerce Event"
                                        "custom_event" -> "Unplanned Custom Event (MPEvent)"
                                        else -> null
                                    }
                                    else -> "Event does not conform to DataPlan"
                                }
                        val matchingValues = ArrayList<UElement>()
                        unresolvedObject.forEachExpression { expression ->
                            val value = expression.resolve()
                            val targetValue = if (error.actual.isNullOrEmpty()) error.key else error.actual
                            if (value.toString() == targetValue) {
                                expression.node.let { matchingValues.add(it) }
                            }
                        }
                        val match = validationResult.data?.match
                        errorMessage = errorMessage ?: validationResult.data?.match?.criteria?.entries?.firstOrNull {
                            it.value == error.key
                        }?.let {entry ->
                                "For ${match?.type}, unexpected ${entry.key} value of \"${entry.value}\". exprected ${error.expected}"
                        }
                        if (matchingValues.size == 1) {
                            context.report(
                                    ISSUE,
                                    matchingValues[0],
                                    context.getLocation(matchingValues[0]),
                                    errorMessage ?: "Event is not in accordance with Data Plan"
                            )
                        } else {
                            context.report(
                                    ISSUE,
                                    reportingNode,
                                    context.getLocation(reportingNode),
                                    errorMessage ?: "Event is not in accordance with Data Plan"
                            )
                        }
                    }
                    Unit
                }
            }
            if (config?.resultsFile?.isEmpty() == false) {
                File(config!!.resultsFile!!).appendText(result?.response?.joinToString { it.toString() } ?: "")
            }
        }

    }

    fun getErrorMessageBySchemaKeyword(schemaKeyword: ViolationSchemaKeywordType, expectedValue: String? = null): String {
        val expectedValueMessage = expectedValue?.let { ": ${it}" } ?: ""
        return when (schemaKeyword) {
            ViolationSchemaKeywordType.Const ->
                "Value did not match the constant specified. Schema keyword const.";
            ViolationSchemaKeywordType.Enum ->
                "Value did not match the set of values specified. Schema keyword enum.";
            ViolationSchemaKeywordType.ExclusiveMaximum ->
                "Value was greater than or equal to the maximum specified. Schema keyword exclusiveMaximum.";
            ViolationSchemaKeywordType.ExclusiveMinimum ->
                "Value was less than or equal to the minimum specified. Schema keyword exclusiveMinimum";
            ViolationSchemaKeywordType.Maximum ->
                "Value was greater than the maximum specified. Schema keyword maximum.";
            ViolationSchemaKeywordType.Minimum ->
                "Value was less than the minimum specified. Schema keyword minimum.";
            ViolationSchemaKeywordType.Format ->
                "Value did not match the format${expectedValueMessage}. Schema keyword format.";
            ViolationSchemaKeywordType.MaxLength ->
                "Value exceeded maximum length specified. Schema keyword maxLength.";
            ViolationSchemaKeywordType.MinLength ->
                "Value shorter than the minimum length specified. Schema keyword minLength.";
            ViolationSchemaKeywordType.Pattern ->
                "Value did not match the regular expression${expectedValueMessage}. Schema keyword pattern.";
            ViolationSchemaKeywordType.AdditionalProperties ->
                "Key was not present in the planned data point";
            ViolationSchemaKeywordType.Required ->
                "Required key missing from the data point. Schema keyword required.";
            ViolationSchemaKeywordType.Type ->
                "Value did not match the data type${expectedValueMessage}. Schema keyword type.";
            ViolationSchemaKeywordType.Unknown ->
                "Unknown schema violation. Schema keyword ${schemaKeyword}";
        }
    }

    enum class ViolationSchemaKeywordType {
        Const,
        Enum,
        ExclusiveMaximum,
        ExclusiveMinimum,
        Maximum,
        Minimum,
        Format,
        MaxLength,
        MinLength,
        Pattern,
        AdditionalProperties,
        Required,
        Type,
        Unknown;

        companion object {
            fun get(value: String?): ViolationSchemaKeywordType {
                return values().firstOrNull { it.toString().toLowerCase() == value?.toLowerCase()} ?: Unknown
            }
        }
    }

    fun JSONObject.attributesToNumbers(): JSONObject {
        val newAttributes = JSONObject()
        val oldAttributes = optJSONObject("attrs")
        if (oldAttributes != null) {
            oldAttributes.keys().forEach {
                val key = it.toString()
                val value = oldAttributes.get(key).toString()
                newAttributes.put(key, value.toIntOrNull() ?: value)
            }
            put("attrs", newAttributes)
        }
        return this
    }
}