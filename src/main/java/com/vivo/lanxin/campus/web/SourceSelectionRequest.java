package com.vivo.lanxin.campus.web;

import java.util.List;

public record SourceSelectionRequest(List<Long> noteIds, List<Long> documentIds) {}
