package org.intellij.sonar;

import com.google.common.base.Optional;
import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import org.apache.commons.lang.StringUtils;
import org.intellij.sonar.index.IssuesIndex;
import org.intellij.sonar.index.IssuesIndexEntry;
import org.intellij.sonar.index.IssuesIndexKey;
import org.intellij.sonar.persistence.IndexComponent;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static com.google.common.base.Optional.fromNullable;

public abstract class SonarLocalInspectionTool extends LocalInspectionTool {

  private static final Logger LOG = Logger.getInstance(SonarLocalInspectionTool.class);



  @Nls
  @NotNull
  @Override
  abstract public String getGroupDisplayName();

  abstract public boolean isNew();

  @NotNull
  @Override
  public String getShortName() {
    return this.getDisplayName().replaceAll("[^a-zA-Z_0-9.-]", "");
  }

  @Nls
  @NotNull
  @Override
  abstract public String getDisplayName();

  @NotNull
  @Override
  abstract public String getStaticDescription();

  @NotNull
  abstract public String getRuleKey();

  @Override
  public boolean isEnabledByDefault() {
    return true;
  }

  @NotNull
  private TextRange getTextRange(@NotNull Document document, int line) {
    int lineStartOffset = document.getLineStartOffset(line - 1);
    int lineEndOffset = document.getLineEndOffset(line - 1);
    return new TextRange(lineStartOffset, lineEndOffset);
  }

  @Nullable
  @Override
  public ProblemDescriptor[] checkFile(@NotNull final PsiFile psiFile, @NotNull final InspectionManager manager, final boolean isOnTheFly) {

    // don't care about non physical files
    final VirtualFile virtualFile = psiFile.getVirtualFile();
    if (virtualFile == null || ProjectFileIndex.SERVICE.getInstance(psiFile.getProject()).getContentRootForFile(virtualFile) == null) {
      return null;
    }

    final Project project = psiFile.getProject();
    Optional<IndexComponent> indexComponent = fromNullable(ServiceManager.getService(project, IndexComponent.class));
    if (!indexComponent.isPresent()) {
      LOG.error(String.format("Cannot retrieve %s", IndexComponent.class.getSimpleName()));
      return null;
    }

    final PsiDocumentManager documentManager = PsiDocumentManager.getInstance(psiFile.getProject());
    final Document document = documentManager.getDocument(psiFile.getContainingFile());
    if (document == null) {
      return null;
    }

    final Collection<ProblemDescriptor> result = new LinkedHashSet<ProblemDescriptor>();

    final Map<IssuesIndexKey, ? extends Set<IssuesIndexEntry>> indexMap = indexComponent.get().getIssuesIndex();
    final Optional<Set<IssuesIndexEntry>> issuesForFile = fromNullable(
        indexMap.get(new IssuesIndexKey(virtualFile.getPath(), isNew(), getRuleKey()))
    );
    if (issuesForFile.isPresent()) {
      for (IssuesIndexEntry issueForFile : issuesForFile.get()) {
        if (null != issueForFile && null != issueForFile.getLine() && issueForFile.getLine() <= document.getLineCount()) {
          TextRange textRange = getTextRange(document, issueForFile.getLine());
          final ProblemHighlightType problemHighlightType = sonarSeverityToProblemHighlightType(issueForFile.getSeverity());

          result.add(manager.createProblemDescriptor(psiFile, textRange,
              String.format("[%s] %s", issueForFile.getSeverity(), issueForFile.getMessage()),
              problemHighlightType,
              false
          ));
        }
      }
    }

    return result.toArray(new ProblemDescriptor[result.size()]);
  }

  private static ProblemHighlightType sonarSeverityToProblemHighlightType(String sonarSeverity) {
    if (StringUtils.isBlank(sonarSeverity)) {
      return ProblemHighlightType.GENERIC_ERROR_OR_WARNING;
    } else {
      sonarSeverity = sonarSeverity.toUpperCase();
      if (SonarSeverity.BLOCKER.toString().equals(sonarSeverity) || SonarSeverity.CRITICAL.toString().equals(sonarSeverity)) {
        return ProblemHighlightType.ERROR;
      } else if (SonarSeverity.MAJOR.toString().equals(sonarSeverity)) {
        return ProblemHighlightType.GENERIC_ERROR_OR_WARNING;
      } else if (SonarSeverity.INFO.toString().equals(sonarSeverity) || SonarSeverity.MINOR.toString().equals(sonarSeverity)) {
        return ProblemHighlightType.WEAK_WARNING;
      } else {
        return ProblemHighlightType.GENERIC_ERROR_OR_WARNING;
      }
    }
  }

}
