package com.npick.clip.application.command;

import com.npick.clip.application.command.prepare.PrepareVideoCommand;
import com.npick.clip.application.command.prepare.PrepareVideoResult;
import com.npick.clip.application.command.prepare.PrepareVideoUseCase;
import com.npick.clip.application.port.VideoInspectionPort;

public final class VideoPreparationService implements PrepareVideoUseCase {
    private final VideoInspectionPort inspection;

    public VideoPreparationService(VideoInspectionPort inspection) {
        this.inspection = inspection;
    }

    @Override
    public PrepareVideoResult prepare(PrepareVideoCommand command) {
        return inspection.inspect(command.content());
    }
}
