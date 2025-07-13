package com.walpro.autobuilder.diff;

import java.util.List;

import com.walpro.autobuilder.tools.diff.ChangedMethod;
import com.walpro.autobuilder.tools.tracker.MethodHashTracker;

public interface CodeDiffService {
    List<ChangedMethod> diffFile(String currentFile,  String currentRelativeCyclePath, String previousRelativeCyclePath, MethodHashTracker tracker);
}
