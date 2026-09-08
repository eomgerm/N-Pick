package com.npick.clip.application.port;

import java.io.InputStream;

import com.npick.clip.application.command.prepare.PrepareVideoResult;

public interface VideoInspectionPort {
    PrepareVideoResult inspect(InputStream content);
}
