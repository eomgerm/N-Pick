package com.npick.clip.application.query.list;

import java.util.List;

import com.npick.clip.application.query.ClipQueryResult;

public record GetClipsResult(List<ClipQueryResult> items, int page, int size, long totalElements) {}
