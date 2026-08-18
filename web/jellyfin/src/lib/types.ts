export interface JellyfinItem {
  Id: string;
  Name: string;
  OriginalTitle?: string;
  ServerId?: string;
  Type: 'Movie' | 'Series' | 'Episode' | 'Season' | 'MusicVideo' | 'Folder' | 'BoxSet' | 'Audio' | 'MusicAlbum' | 'MusicArtist' | 'Playlist' | 'Video';
  Overview?: string;
  Taglines?: string[];
  Genres?: string[];
  CommunityRating?: number;
  OfficialRating?: string;
  RunTimeTicks?: number; // 10,000 ticks = 1 ms
  ProductionYear?: number;
  PremiereDate?: string;
  EndDate?: string;
  IndexNumber?: number; // Episode / Track number
  ParentIndexNumber?: number; // Season / Disc number
  SeriesName?: string;
  SeriesId?: string;
  SeasonName?: string;
  SeasonId?: string;
  Album?: string;
  AlbumId?: string;
  AlbumArtist?: string;
  Artists?: string[];
  PrimaryImageTag?: string;
  BackdropImageTags?: string[];
  ImageTags?: Record<string, string>;
  Container?: string;
  VideoType?: string;
  MediaType?: string;
  IsFolder?: boolean;
  ChildCount?: number;
  UserData?: {
    PlaybackPositionTicks?: number;
    PlayCount?: number;
    IsFavorite?: boolean;
    Played?: boolean;
    LastPlayedDate?: string;
  };
  MediaSources?: JellyfinMediaSource[];
  MediaStreams?: JellyfinMediaStream[];
  // Extra helper fields for demo / UI
  streamUrl?: string;
  posterUrl?: string;
  backdropUrl?: string;
  seasons?: JellyfinSeason[];
  tracks?: JellyfinItem[];
}

export interface JellyfinSeason {
  Id: string;
  Name: string;
  IndexNumber: number;
  SeriesId: string;
  Episodes: JellyfinItem[];
}

export interface JellyfinMediaSource {
  Id: string;
  Container?: string;
  Path?: string;
  Protocol?: string;
  Bitrate?: number;
  DirectStreamUrl?: string;
  TranscodingUrl?: string;
  SupportsDirectPlay?: boolean;
  SupportsDirectStream?: boolean;
  SupportsTranscoding?: boolean;
}

export interface JellyfinMediaStream {
  Type: 'Audio' | 'Video' | 'Subtitle';
  Codec?: string;
  Language?: string;
  DisplayTitle?: string;
  IsDefault?: boolean;
  IsForced?: boolean;
  Height?: number;
  Width?: number;
  AspectRatio?: string;
  AverageFrameRate?: number;
  Channels?: number;
  SampleRate?: number;
  Index?: number;
}

export interface JellyfinUser {
  Id: string;
  Name: string;
  ServerId: string;
  HasPassword?: boolean;
  Policy?: {
    IsAdministrator?: boolean;
  };
}

export interface ServerConfig {
  url: string;
  token: string;
  userId: string;
  username: string;
  serverName: string;
  isDemo: boolean;
  connected: boolean;
}

export interface SavedAccount {
  id: string; // url + '_' + userId
  url: string;
  token: string;
  userId: string;
  username: string;
  serverName: string;
  serverId?: string;
  isDemo?: boolean;
  lastActive: number;
}

export interface VisualMetadata {
  title?: string;
  year?: string;
  overview?: string;
  genres?: string[];
  posterUrl?: string;
  backdropUrl?: string;
  season?: number;
  episode?: number;
  episodeTitle?: string;
  album?: string;
  artist?: string;
  duration?: number;
}

export interface PlayBridgeItem {
  id?: string;
  url: string;
  title?: string;
  contentType?: string;
  posterUrl?: string;
  metadata?: VisualMetadata;
  customData?: Record<string, any>;
}

export interface PlayBridgeCastPayload {
  url?: string;
  title?: string;
  contentType?: string;
  posterUrl?: string;
  metadata?: VisualMetadata;
  items?: PlayBridgeItem[];
  startIndex?: number;
  localNetwork?: boolean;
  customData?: Record<string, any>;
}

export interface PlayBridgeLinkSession {
  sessionId: string;
  addEventListener(event: 'statechange' | 'needitems' | 'ended', callback: (event: any) => void): void;
  removeEventListener(event: string, callback: (event: any) => void): void;
  provideItems(requestId: string, payload: { items: PlayBridgeItem[]; endOfList?: boolean }): Promise<void>;
  jump(index: number): Promise<void>;
  unlink(): Promise<void>;
}

export interface PlayBridgeAPI {
  cast(payload: PlayBridgeCastPayload | PlayBridgeItem[]): void;
  linkCast?(options: {
    items: PlayBridgeItem[];
    startIndex?: number;
    metadata?: VisualMetadata;
    localNetwork?: boolean;
  }): Promise<PlayBridgeLinkSession>;
  capabilities?: {
    linkedCast?: boolean;
    mediaControls?: boolean;
  };
}

declare global {
  interface Window {
    playbridge?: PlayBridgeAPI;
  }
}
