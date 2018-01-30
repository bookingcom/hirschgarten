/*
 * Copyright 2016 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.idea.blaze.base;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.base.Joiner;
import com.google.idea.blaze.base.io.FileOperationProvider;
import com.google.idea.blaze.base.io.VirtualFileSystemProvider;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.application.Result;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.ex.temp.TempFileSystem;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.testFramework.LightPlatformTestCase;
import com.intellij.testFramework.fixtures.TempDirTestFixture;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import javax.annotation.Nullable;

/** Creates temp files for integration tests. */
public class TestFileSystem {
  private final Project project;
  private final TempDirTestFixture tempDirTestFixture;

  public TestFileSystem(Project project, TempDirTestFixture tempDirTestFixture) {
    this.project = project;
    this.tempDirTestFixture = tempDirTestFixture;
  }

  /** Returns the root directory of the file system */
  public String getRootDir() {
    return LightPlatformTestCase.getSourceRoot().getPath();
  }

  /** Creates an empty file in the temp file system */
  public VirtualFile createFile(String filePath) {
    filePath = makePathRelativeToTestFixture(filePath);
    return tempDirTestFixture.createFile(filePath);
  }

  /** Creates a file with the specified contents in the temp file system */
  public VirtualFile createFile(String filePath, String... contentLines) {
    return createFile(filePath, Joiner.on("\n").join(contentLines));
  }

  /** Creates a file with the specified contents in the temp file system */
  public VirtualFile createFile(String filePath, String contents) {
    filePath = makePathRelativeToTestFixture(filePath);
    try {
      return tempDirTestFixture.createFile(filePath, contents);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /** Creates a directory in the temp file system */
  public VirtualFile createDirectory(String path) {
    path = makePathRelativeToTestFixture(path);
    try {
      return tempDirTestFixture.findOrCreateDir(path);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /** Creates a psi directory in the temp file system */
  public PsiDirectory createPsiDirectory(String path) {
    return getPsiDirectory(createDirectory(path));
  }

  /** Creates a psi file in the temp file system */
  public PsiFile createPsiFile(String filePath) {
    filePath = makePathRelativeToTestFixture(filePath);
    return getPsiFile(tempDirTestFixture.createFile(filePath));
  }

  /** Creates a psi file with the specified contents and file path in the temp file system */
  public PsiFile createPsiFile(String filePath, String... contentLines) {
    return getPsiFile(createFile(filePath, contentLines));
  }

  /** Finds PsiFile, and asserts that it's not null. */
  public PsiFile getPsiFile(VirtualFile file) {
    return new ReadAction<PsiFile>() {
      @Override
      protected void run(Result<PsiFile> result) throws Throwable {
        PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
        assertThat(psiFile).isNotNull();
        result.setResult(psiFile);
      }
    }.execute().getResultObject();
  }

  /** Finds PsiDirectory, and asserts that it's not null. */
  public PsiDirectory getPsiDirectory(VirtualFile file) {
    return new ReadAction<PsiDirectory>() {
      @Override
      protected void run(Result<PsiDirectory> result) throws Throwable {
        PsiDirectory psiFile = PsiManager.getInstance(project).findDirectory(file);
        assertThat(psiFile).isNotNull();
        result.setResult(psiFile);
      }
    }.execute().getResultObject();
  }

  @Nullable
  public VirtualFile findFile(String filePath) {
    VirtualFile vf = TempFileSystem.getInstance().findFileByPath(filePath);
    if (vf != null) {
      return vf;
    }
    File file = new File(filePath);
    if (file.isAbsolute()) {
      // try looking for a physical file
      return LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file);
    }
    filePath = makePathRelativeToTestFixture(filePath);
    return tempDirTestFixture.getFile(filePath);
  }

  public VirtualFile findOrCreateDirectory(String path) throws IOException {
    path = makePathRelativeToTestFixture(path);
    return tempDirTestFixture.findOrCreateDir(path);
  }

  /**
   * Absolute file paths are prohibited -- the TempDirTestFixture used in these tests will prepend
   * it's own root to the path.
   */
  private String makePathRelativeToTestFixture(String filePath) {
    if (!FileUtil.isAbsolute(filePath)) {
      return filePath;
    }
    String tempDirPath = getRootDir();
    assertThat(FileUtil.isAncestor(tempDirPath, filePath, true)).isTrue();
    return FileUtil.getRelativePath(tempDirPath, filePath, File.separatorChar);
  }

  /** Redirects file system checks via the TempFileSystem used for these tests. */
  public static class MockFileOperationProvider extends FileOperationProvider {

    final TempFileSystem fileSystem = TempFileSystem.getInstance();

    @Override
    public boolean exists(File file) {
      VirtualFile vf = getVirtualFile(file);
      return vf != null && vf.exists();
    }

    @Override
    public boolean isDirectory(File file) {
      VirtualFile vf = getVirtualFile(file);
      return vf != null && vf.isDirectory();
    }

    @Override
    public boolean isFile(File file) {
      VirtualFile vf = getVirtualFile(file);
      return vf != null && vf.exists() && !vf.isDirectory();
    }

    @Override
    public File[] listFiles(File file) {
      VirtualFile vf = getVirtualFile(file);
      if (vf == null) {
        return null;
      }
      VirtualFile[] children = vf.getChildren();
      if (children == null) {
        return null;
      }
      return Arrays.stream(vf.getChildren()).map((f) -> new File(f.getPath())).toArray(File[]::new);
    }

    private VirtualFile getVirtualFile(File file) {
      return fileSystem.findFileByPath(file.getPath());
    }
  }

  /** Redirects VirtualFileSystem operations to the TempFileSystem used for these tests. */
  public static class TempVirtualFileSystemProvider implements VirtualFileSystemProvider {

    final TempFileSystem fileSystem = TempFileSystem.getInstance();

    @Override
    public LocalFileSystem getSystem() {
      return fileSystem;
    }
  }
}
