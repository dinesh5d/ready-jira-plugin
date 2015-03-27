package com.smartbear.ready.plugin.jira.actions;

import com.atlassian.jira.rest.client.api.domain.CimFieldInfo;
import com.eviware.soapui.impl.wsdl.WsdlProject;
import com.eviware.soapui.model.ModelItem;
import com.eviware.soapui.model.testsuite.TestCase;
import com.eviware.soapui.model.testsuite.TestStep;
import com.eviware.soapui.model.testsuite.TestSuite;
import com.eviware.soapui.plugins.ActionConfiguration;
import com.eviware.soapui.support.StringUtils;
import com.eviware.soapui.support.UISupport;
import com.eviware.soapui.support.action.support.AbstractSoapUIAction;
import com.eviware.soapui.support.types.StringToStringMap;
import com.eviware.x.form.XForm;
import com.eviware.x.form.XFormDialog;
import com.eviware.x.form.XFormDialogBuilder;
import com.eviware.x.form.XFormFactory;
import com.eviware.x.form.XFormField;
import com.eviware.x.form.XFormFieldListener;
import com.eviware.x.form.XFormOptionsField;
import com.eviware.x.form.XFormTextField;
import com.google.inject.Inject;
import com.smartbear.ready.functional.actions.FunctionalActionGroups;
import com.smartbear.ready.plugin.jira.dialog.BugInfoDialogConsts;
import com.smartbear.ready.plugin.jira.impl.AttachmentAddingResult;
import com.smartbear.ready.plugin.jira.impl.IssueCreationResult;
import com.smartbear.ready.plugin.jira.impl.IssueInfoDialog;
import com.smartbear.ready.plugin.jira.impl.JiraProvider;
import com.smartbear.ready.plugin.jira.impl.Utils;

import java.net.URI;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ActionConfiguration(actionGroup = FunctionalActionGroups.FUNCTIONAL_MODULE_TOOLBAR_ACTIONS, targetType = ModelItem.class, isToolbarAction = true,
        iconPath = "com/smartbear/ready/plugin/jira/icons/Create-new-bug-tracker-issue-icon_20-20-px.png")
public class CreateNewBugAction extends AbstractSoapUIAction<ModelItem> {
    private static String NEW_ISSUE_DIALOG_CAPTION = "Create new Jira issue";
    private XFormDialog dialogOne;
    private XFormDialog dialogTwo;
    String selectedProject, selectedIssueType;

    private static final List<String> skippedFieldKeys = Arrays.asList("summary", "project", "issuetype", "description");

    @Inject
    public CreateNewBugAction() {
        super("Create Jira issue", "Specifies the required fields to create new issue in Jira");
    }

    @Override
    public void perform(ModelItem target, Object o) {
        JiraProvider bugTrackerProvider = JiraProvider.getProvider();
        if (!bugTrackerProvider.settingsComplete()) {
            UISupport.showErrorMessage("Bug tracker settings are not completely specified.");
            return;
        }
        bugTrackerProvider.setActiveItem(target);//TODO: check if it's really target
        List<String> projects = bugTrackerProvider.getListOfAllProjects();
        if (projects == null || projects.size() == 0) {
            UISupport.showErrorMessage("No available Jira projects.");
            return;
        }
        dialogOne = createFirstDialog(bugTrackerProvider);
        if (dialogOne.show()) {
            dialogTwo = createSecondDialog(bugTrackerProvider, selectedProject, selectedIssueType);
            if (dialogTwo.show()) {
                createIssue(bugTrackerProvider);
            }
        }
    }

    private void createIssue(JiraProvider bugTrackerProvider) {
        StringToStringMap values = dialogTwo.getValues();
        String summary = values.get(BugInfoDialogConsts.ISSUE_SUMMARY, null);
        String description = values.get(BugInfoDialogConsts.ISSUE_DESCRIPTION, null);
        String projectKey = selectedProject;
        String issueType = selectedIssueType;
        String priority = values.get(BugInfoDialogConsts.ISSUE_PRIORITY, null);
        Map<String, String> extraValues = new HashMap<String, String>();
        for (Map.Entry<String, CimFieldInfo> entry : bugTrackerProvider.getProjectRequiredFields(projectKey).get(projectKey).get(issueType).entrySet()) {
            String key = entry.getKey();
            if (skippedFieldKeys.contains(key)) {
                continue;
            }
            extraValues.put(entry.getKey(), values.get(entry.getValue().getName()));
        }
        IssueCreationResult result = bugTrackerProvider.createIssue(projectKey, issueType, priority, summary, description, extraValues);
        if (result.getSuccess()) {
            boolean isAttachmentSuccess = true;
            URI newIssueAttachURI = bugTrackerProvider.getIssue(result.getIssue().getKey()).getAttachmentsUri();
            StringBuilder resultError = new StringBuilder();
            if (dialogTwo.getBooleanValue(BugInfoDialogConsts.ATTACH_SOAPUI_LOG)) {
                AttachmentAddingResult attachResult = bugTrackerProvider.attachFile(newIssueAttachURI, bugTrackerProvider.getActiveItemName() + ".log", bugTrackerProvider.getSoapUIExecutionLog());
                if (!attachResult.getSuccess()) {
                    isAttachmentSuccess = false;
                    resultError.append(attachResult.getError());
                    resultError.append("\r\n");
                }
            }

            if (dialogTwo.getBooleanValue(BugInfoDialogConsts.ATTACH_PROJECT)) {
                AttachmentAddingResult attachResult = bugTrackerProvider.attachFile(newIssueAttachURI, bugTrackerProvider.getRootProjectName() + ".xml", bugTrackerProvider.getRootProject());
                if (!attachResult.getSuccess()) {
                    isAttachmentSuccess = false;
                    resultError.append(attachResult.getError());
                    resultError.append("\r\n");
                }
            }

            String attachAnyFileValue = dialogTwo.getValue(BugInfoDialogConsts.ATTACH_ANY_FILE);
            if (!StringUtils.isNullOrEmpty(dialogTwo.getValue(BugInfoDialogConsts.ATTACH_ANY_FILE))) {
                AttachmentAddingResult attachResult = bugTrackerProvider.attachFile(newIssueAttachURI, attachAnyFileValue);
                if (!attachResult.getSuccess()) {
                    isAttachmentSuccess = false;
                    resultError.append(attachResult.getError());
                }
            }

            if (!isAttachmentSuccess) {
                UISupport.showErrorMessage(resultError.toString());
            } else {
                IssueInfoDialog.showDialog(issueType, bugTrackerProvider.getBugTrackerSettings().getUrl().concat("/browse/").concat(result.getIssue().getKey()), result.getIssue().getKey());//TODO: make link correct for all cases
            }

        } else {
            UISupport.showErrorMessage(result.getError());
        }
    }

    private void addExtraRequiredFields(XForm baseDialog, JiraProvider bugTrackerProvider, String selectedProject, String selectedIssueType) {
        Map<String, Map<String, Map<String, CimFieldInfo>>> allRequiredFields = bugTrackerProvider.getProjectRequiredFields(selectedProject);
        for (Map.Entry<String, CimFieldInfo> field : allRequiredFields.get(selectedProject).get(selectedIssueType).entrySet()) {
            String key = field.getKey();
            if (skippedFieldKeys.contains(key)) {
                continue;
            }
            CimFieldInfo fieldInfo = field.getValue();
            if (fieldInfo.getAllowedValues() != null) {
                if (fieldInfo.getName().equals("Component/s")){
                    Object[] components = bugTrackerProvider.getProjectComponentNames(selectedProject);
                    XFormOptionsField combo = baseDialog.addComboBox(fieldInfo.getName(), components, fieldInfo.getName());
                } else {
                    Object[] values = Utils.IterableValuesToArray(fieldInfo.getAllowedValues());
                    if (values.length > 0) {
                        XFormOptionsField combo = baseDialog.addComboBox(fieldInfo.getName(), values, fieldInfo.getName());
                    } else {
                        XFormTextField textField = baseDialog.addTextField(fieldInfo.getName(), field.getKey(), XForm.FieldType.TEXT);
                    }
                }
            } else {
                XFormTextField textField = baseDialog.addTextField(fieldInfo.getName(), field.getKey(), XForm.FieldType.TEXT);
            }
        }
    }

    private XFormDialog createSecondDialog(final JiraProvider bugTrackerProvider, final String selectedProject, final String selectedIssueType) {
        XFormDialogBuilder builder = XFormFactory.createDialogBuilder(NEW_ISSUE_DIALOG_CAPTION);
        XForm form = builder.createForm("Basic");
        String selectedPriority = (String) bugTrackerProvider.getListOfPriorities().toArray()[0];
        form.addComboBox(BugInfoDialogConsts.ISSUE_PRIORITY, bugTrackerProvider.getListOfPriorities().toArray(), selectedPriority);
        form.addTextField(BugInfoDialogConsts.ISSUE_SUMMARY, "Issue summary", XForm.FieldType.TEXT);
        form.addTextField(BugInfoDialogConsts.ISSUE_DESCRIPTION, "Issue description", XForm.FieldType.TEXTAREA);
        addExtraRequiredFields(form, bugTrackerProvider, selectedProject, selectedIssueType);
        form.addCheckBox(BugInfoDialogConsts.ATTACH_SOAPUI_LOG, "");
        form.addCheckBox(BugInfoDialogConsts.ATTACH_PROJECT, "");
        form.addTextField(BugInfoDialogConsts.ATTACH_ANY_FILE, "Attach file", XForm.FieldType.FILE);
        return builder.buildDialog(builder.buildOkCancelActions(), "Please specify issue options", null);
    }

    private XFormDialog createFirstDialog(final JiraProvider bugTrackerProvider) {
        XFormDialogBuilder builder = XFormFactory.createDialogBuilder(NEW_ISSUE_DIALOG_CAPTION);
        XForm form = builder.createForm("Basic");
        List<String> allProjectsList = bugTrackerProvider.getListOfAllProjects();
        XFormOptionsField projectsCombo = form.addComboBox(BugInfoDialogConsts.TARGET_ISSUE_PROJECT, allProjectsList.toArray(), BugInfoDialogConsts.TARGET_ISSUE_PROJECT);
        if (StringUtils.isNullOrEmpty(selectedProject)) {
            selectedProject = (String) (allProjectsList.toArray()[0]);
        }
        projectsCombo.setValue(selectedProject);
        projectsCombo.addFormFieldListener(new XFormFieldListener() {
            @Override
            public void valueChanged(XFormField xFormField, String newValue, String oldValue) {
                selectedProject = newValue;
            }
        });
        if (StringUtils.isNullOrEmpty(selectedIssueType)) {
            selectedIssueType = (String) bugTrackerProvider.getListOfAllIssueTypes(selectedProject).toArray()[0];
        }
        XFormOptionsField issueTypesCombo = form.addComboBox(BugInfoDialogConsts.ISSUE_TYPE, bugTrackerProvider.getListOfAllIssueTypes(selectedProject).toArray(), BugInfoDialogConsts.ISSUE_TYPE);
        issueTypesCombo.setValue(selectedIssueType);
        issueTypesCombo.addFormFieldListener(new XFormFieldListener() {
            @Override
            public void valueChanged(XFormField xFormField, String newValue, String oldValue) {
                selectedIssueType = newValue;
            }
        });
        return builder.buildDialog(builder.buildOkCancelActions(), "Please enter the required project and issue type", null);
    }

    @Override
    public boolean shouldBeEnabledFor(ModelItem modelItem) {
        if (modelItem instanceof WsdlProject || modelItem instanceof TestCase ||
                modelItem instanceof TestSuite || modelItem instanceof TestStep) {
            return true;
        }

        return false;
    }
}
