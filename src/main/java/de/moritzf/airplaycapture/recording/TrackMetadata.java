package de.moritzf.airplaycapture.recording;

/** Track details a sender announces; fields it leaves out are null. */
public record TrackMetadata(
    String title,
    String artist,
    String album,
    String genre,
    Integer trackNumber,
    Integer year,
    Integer mediaKind
) {}
