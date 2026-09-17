"""Strict candidate and evidence vocabulary for job-api entity extraction v1."""

# These field names are the job-api wire contract.
# ruff: noqa: N815

from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

EntityType = Literal["person", "organization", "location", "facility", "keyword", "event"]
TagType = Literal[
    "person",
    "organization",
    "location",
    "facility",
    "keyword",
    "event",
    "season",
    "weather",
    "scene_type",
]


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, strict=True)


class KeyframeEvidence(StrictModel):
    sourceRefType: Literal["keyframe"]
    sceneIndex: int = Field(ge=0)
    timestampMs: int = Field(ge=0)
    storageKey: str = Field(min_length=1)


class OcrEvidence(StrictModel):
    sourceRefType: Literal["ocr_observation"]
    sceneIndex: int = Field(ge=0)
    timestampMs: int = Field(ge=0)
    storageKey: str = Field(min_length=1)
    observationIndex: int = Field(ge=0)


class TranscriptEvidence(StrictModel):
    sourceRefType: Literal["scene"]
    sceneIndex: int = Field(ge=0)
    storageKey: str = Field(min_length=1)
    segmentId: str = Field(min_length=1)
    s: int = Field(ge=0)
    e: int = Field(gt=0)
    sourceDetail: Literal["uploaded", "embedded", "asr"]

    @model_validator(mode="after")
    def interval(self) -> "TranscriptEvidence":
        if self.s >= self.e:
            raise ValueError("empty transcript interval")
        return self


Evidence = Annotated[
    KeyframeEvidence | OcrEvidence | TranscriptEvidence, Field(discriminator="sourceRefType")
]


class Candidate(StrictModel):
    type: TagType
    value: str = Field(min_length=1)
    source: Literal["rule", "vlm"]
    confidence: float = Field(ge=0, le=1, allow_inf_nan=False)
    evidence: tuple[Evidence, ...] = Field(min_length=1)

    @model_validator(mode="after")
    def scope(self) -> "Candidate":
        if not self.value.strip():
            raise ValueError("empty display value")
        if self.source == "rule" and self.type in {"season", "weather", "scene_type"}:
            raise ValueError("classification candidates belong to VLM")
        return self


class SceneCandidates(StrictModel):
    sceneIndex: int = Field(ge=0)
    tagCandidates: tuple[Candidate, ...]

    @model_validator(mode="after")
    def same_scene(self) -> "SceneCandidates":
        if any(
            ref.sceneIndex != self.sceneIndex for tag in self.tagCandidates for ref in tag.evidence
        ):
            raise ValueError("cross-scene evidence")
        return self


class Output(StrictModel):
    scenes: tuple[SceneCandidates, ...]

    @model_validator(mode="after")
    def unique_scenes(self) -> "Output":
        if len({scene.sceneIndex for scene in self.scenes}) != len(self.scenes):
            raise ValueError("duplicate scene")
        return self
