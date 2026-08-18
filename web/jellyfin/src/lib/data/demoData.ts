import type { JellyfinItem } from '../types';

const GTV = 'https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/';
const MUX_HLS = 'https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8';
const TEARS_HLS = 'https://bitdash-a.akamaihd.net/content/sintel/hls/playlist.m3u8';

export const DEMO_MOVIES: JellyfinItem[] = [
  {
    Id: 'demo-movie-1',
    Name: 'Big Buck Bunny',
    Type: 'Movie',
    ProductionYear: 2008,
    CommunityRating: 8.4,
    OfficialRating: 'G',
    RunTimeTicks: 5960000000, // ~9.9 mins
    Overview: 'A large and lovable rabbit takes poetic, gentle revenge on three forest bullies—a flying squirrel and two sneaky rodents—who destroy his forest tranquility and kill two innocent butterflies.',
    Taglines: ['A large rabbit with an even bigger heart'],
    Genres: ['Animation', 'Comedy', 'Family'],
    streamUrl: GTV + 'BigBuckBunny.mp4',
    posterUrl: GTV + 'images/BigBuckBunny.jpg',
    backdropUrl: 'https://images.unsplash.com/photo-1579783902614-a3fb3927b675?auto=format&fit=crop&w=1600&q=80',
    MediaStreams: [
      { Type: 'Video', Codec: 'h264', DisplayTitle: '1080p H.264', Height: 1080, Width: 1920, AspectRatio: '16:9', AverageFrameRate: 24 },
      { Type: 'Audio', Codec: 'aac', Language: 'eng', DisplayTitle: 'English (AAC 5.1)', Channels: 6 }
    ],
    UserData: { PlaybackPositionTicks: 1200000000, Played: false, IsFavorite: true }
  },
  {
    Id: 'demo-movie-2',
    Name: 'Sintel',
    Type: 'Movie',
    ProductionYear: 2010,
    CommunityRating: 8.8,
    OfficialRating: 'PG-13',
    RunTimeTicks: 8880000000, // ~14.8 mins
    Overview: 'A lonely young woman named Sintel embarks on a perilous and tragic quest across a harsh desert world to rescue her companion, a tiny dragon she nursed back to health called Scales.',
    Taglines: ['The search for a lost friend leads to an unforeseen fate'],
    Genres: ['Animation', 'Fantasy', 'Action', 'Drama'],
    streamUrl: GTV + 'Sintel.mp4',
    posterUrl: GTV + 'images/Sintel.jpg',
    backdropUrl: 'https://images.unsplash.com/photo-1518709268805-4e9042af9f23?auto=format&fit=crop&w=1600&q=80',
    MediaStreams: [
      { Type: 'Video', Codec: 'h264', DisplayTitle: '4K Ultra HD (H.264)', Height: 2160, Width: 3840, AspectRatio: '16:9', AverageFrameRate: 24 },
      { Type: 'Audio', Codec: 'ac3', Language: 'eng', DisplayTitle: 'English (Dolby Digital 5.1)', Channels: 6 },
      { Type: 'Subtitle', Codec: 'subrip', Language: 'eng', DisplayTitle: 'English [CC]' }
    ],
    UserData: { PlaybackPositionTicks: 0, Played: false, IsFavorite: true }
  },
  {
    Id: 'demo-movie-3',
    Name: 'Tears of Steel',
    Type: 'Movie',
    ProductionYear: 2012,
    CommunityRating: 8.1,
    OfficialRating: 'PG-13',
    RunTimeTicks: 7340000000, // ~12.2 mins
    Overview: 'Set in a dystopian future Amsterdam, a squad of human warriors and scientists attempt to rewrite the past in a desperate bid to rescue the Earth from a destructive army of sentient cyborg robots.',
    Taglines: ['Exploring a dystopian future where humanity and cybernetics collide'],
    Genres: ['Sci-Fi', 'VFX Showcase', 'Cyberpunk'],
    streamUrl: GTV + 'TearsOfSteel.mp4',
    posterUrl: GTV + 'images/TearsOfSteel.jpg',
    backdropUrl: 'https://images.unsplash.com/photo-1509198397868-475647b2a1e5?auto=format&fit=crop&w=1600&q=80',
    MediaStreams: [
      { Type: 'Video', Codec: 'h264', DisplayTitle: '4K CinemaScope (H.264)', Height: 1714, Width: 4096, AspectRatio: '2.39:1', AverageFrameRate: 24 },
      { Type: 'Audio', Codec: 'dts', Language: 'eng', DisplayTitle: 'English (DTS-HD 7.1)', Channels: 8 }
    ],
    UserData: { PlaybackPositionTicks: 0, Played: true, IsFavorite: false }
  },
  {
    Id: 'demo-movie-4',
    Name: 'Elephants Dream',
    Type: 'Movie',
    ProductionYear: 2006,
    CommunityRating: 7.9,
    OfficialRating: 'PG',
    RunTimeTicks: 6540000000, // ~10.9 mins
    Overview: 'Two eccentric wanderers—Proog, the seasoned elder guide, and Emo, the sceptical youth—explore the surreal and infinite mechanical workings of a gigantic living machine universe.',
    Taglines: ['The world is a machine created by human thought'],
    Genres: ['Animation', 'Surrealism', 'Sci-Fi'],
    streamUrl: GTV + 'ElephantsDream.mp4',
    posterUrl: GTV + 'images/ElephantsDream.jpg',
    backdropUrl: 'https://images.unsplash.com/photo-1451187580459-43490279c0fa?auto=format&fit=crop&w=1600&q=80',
    MediaStreams: [
      { Type: 'Video', Codec: 'h264', DisplayTitle: '1080p H.264', Height: 1080, Width: 1920, AspectRatio: '16:9' },
      { Type: 'Audio', Codec: 'aac', Language: 'eng', DisplayTitle: 'English (AAC 5.1)', Channels: 6 }
    ],
    UserData: { PlaybackPositionTicks: 3100000000, Played: false, IsFavorite: false }
  },
  {
    Id: 'demo-movie-5',
    Name: 'Mux HLS Adaptive Test',
    Type: 'Movie',
    ProductionYear: 2024,
    CommunityRating: 9.0,
    OfficialRating: 'NR',
    RunTimeTicks: 18000000000,
    Overview: 'High-performance multi-bitrate HLS adaptive stream from Mux. Exercises seamless resolution switching, segment caching, and native PlayBridge receiver stream demuxing.',
    Taglines: ['Multi-bitrate adaptive live HLS stream'],
    Genres: ['Live Test', 'HLS Stream', 'Adaptive Bitrate'],
    streamUrl: MUX_HLS,
    posterUrl: GTV + 'images/ForBiggerBlazes.jpg',
    backdropUrl: 'https://images.unsplash.com/photo-1478760329108-5c3ed9d495a0?auto=format&fit=crop&w=1600&q=80',
    MediaStreams: [
      { Type: 'Video', Codec: 'h264', DisplayTitle: 'HLS 1080p/720p/480p Adaptive', Height: 1080, Width: 1920 },
      { Type: 'Audio', Codec: 'aac', Language: 'eng', DisplayTitle: 'Stereo AAC 192kbps', Channels: 2 }
    ],
    UserData: { PlaybackPositionTicks: 0, Played: false, IsFavorite: true }
  }
];

export const DEMO_SHOWS: JellyfinItem[] = [
  {
    Id: 'demo-series-1',
    Name: 'Chronicles of the Open Canvas',
    Type: 'Series',
    ProductionYear: 2023,
    CommunityRating: 9.3,
    OfficialRating: 'TV-14',
    Overview: 'An anthology series tracking visionary open-source CGI creators as they push the limits of computer animation, digital cinematography, and narrative storytelling.',
    Taglines: ['Stories told through the lens of pure open creativity'],
    Genres: ['Documentary', 'Animation', 'Technology', 'Sci-Fi'],
    posterUrl: 'https://images.unsplash.com/photo-1536440136628-849c177e76a1?auto=format&fit=crop&w=600&q=80',
    backdropUrl: 'https://images.unsplash.com/photo-1518709268805-4e9042af9f23?auto=format&fit=crop&w=1600&q=80',
    seasons: [
      {
        Id: 'demo-season-1',
        Name: 'Season 1',
        IndexNumber: 1,
        SeriesId: 'demo-series-1',
        Episodes: [
          {
            Id: 'demo-ep-101',
            Name: 'The Awakening Rabbit',
            Type: 'Episode',
            IndexNumber: 1,
            ParentIndexNumber: 1,
            SeriesName: 'Chronicles of the Open Canvas',
            SeriesId: 'demo-series-1',
            SeasonName: 'Season 1',
            SeasonId: 'demo-season-1',
            RunTimeTicks: 5960000000,
            Overview: 'The forest comes alive as a gentle giant discovers malicious tricks plotted by rival rodents.',
            streamUrl: GTV + 'BigBuckBunny.mp4',
            posterUrl: GTV + 'images/BigBuckBunny.jpg',
            backdropUrl: GTV + 'images/BigBuckBunny.jpg',
            UserData: { Played: true }
          },
          {
            Id: 'demo-ep-102',
            Name: 'The Dreamer’s Machine',
            Type: 'Episode',
            IndexNumber: 2,
            ParentIndexNumber: 1,
            SeriesName: 'Chronicles of the Open Canvas',
            SeriesId: 'demo-series-1',
            SeasonName: 'Season 1',
            SeasonId: 'demo-season-1',
            RunTimeTicks: 6540000000,
            Overview: 'Proog leads Emo through the clockwork labyrinth of a colossal thinking machine.',
            streamUrl: GTV + 'ElephantsDream.mp4',
            posterUrl: GTV + 'images/ElephantsDream.jpg',
            backdropUrl: GTV + 'images/ElephantsDream.jpg',
            UserData: { PlaybackPositionTicks: 2400000000, Played: false }
          },
          {
            Id: 'demo-ep-103',
            Name: 'Wings of Winter',
            Type: 'Episode',
            IndexNumber: 3,
            ParentIndexNumber: 1,
            SeriesName: 'Chronicles of the Open Canvas',
            SeriesId: 'demo-series-1',
            SeasonName: 'Season 1',
            SeasonId: 'demo-season-1',
            RunTimeTicks: 8880000000,
            Overview: 'Across snowcapped peaks and dangerous ravines, the hunt for the missing baby dragon reaches its climax.',
            streamUrl: GTV + 'Sintel.mp4',
            posterUrl: GTV + 'images/Sintel.jpg',
            backdropUrl: GTV + 'images/Sintel.jpg',
            UserData: { Played: false }
          }
        ]
      },
      {
        Id: 'demo-season-2',
        Name: 'Season 2: Cyber Future',
        IndexNumber: 2,
        SeriesId: 'demo-series-1',
        Episodes: [
          {
            Id: 'demo-ep-201',
            Name: 'Cyborg Dawn',
            Type: 'Episode',
            IndexNumber: 1,
            ParentIndexNumber: 2,
            SeriesName: 'Chronicles of the Open Canvas',
            SeriesId: 'demo-series-1',
            SeasonName: 'Season 2: Cyber Future',
            SeasonId: 'demo-season-2',
            RunTimeTicks: 7340000000,
            Overview: 'Humanity prepares for its final stand in futuristic Amsterdam using temporal rewinding.',
            streamUrl: GTV + 'TearsOfSteel.mp4',
            posterUrl: GTV + 'images/TearsOfSteel.jpg',
            backdropUrl: GTV + 'images/TearsOfSteel.jpg',
            UserData: { Played: false }
          },
          {
            Id: 'demo-ep-202',
            Name: 'Cosmic Escape',
            Type: 'Episode',
            IndexNumber: 2,
            ParentIndexNumber: 2,
            SeriesName: 'Chronicles of the Open Canvas',
            SeriesId: 'demo-series-1',
            SeasonName: 'Season 2: Cyber Future',
            SeasonId: 'demo-season-2',
            RunTimeTicks: 3000000000,
            Overview: 'High octane space escapade showcasing real-time physics and lighting.',
            streamUrl: GTV + 'ForBiggerEscapes.mp4',
            posterUrl: GTV + 'images/ForBiggerEscapes.jpg',
            backdropUrl: GTV + 'images/ForBiggerEscapes.jpg',
            UserData: { Played: false }
          }
        ]
      }
    ]
  },
  {
    Id: 'demo-series-2',
    Name: 'Streamline 4K Cinema',
    Type: 'Series',
    ProductionYear: 2024,
    CommunityRating: 8.9,
    OfficialRating: 'TV-G',
    Overview: 'A curated video exhibition exploring cutting-edge 4K resolution, High Dynamic Range (HDR10+ / Dolby Vision), and spatial surround sound.',
    Taglines: ['A visual feast of ultra high definition'],
    Genres: ['Documentary', 'Shorts', 'Nature'],
    posterUrl: 'https://images.unsplash.com/photo-1478760329108-5c3ed9d495a0?auto=format&fit=crop&w=600&q=80',
    backdropUrl: 'https://images.unsplash.com/photo-1509198397868-475647b2a1e5?auto=format&fit=crop&w=1600&q=80',
    seasons: [
      {
        Id: 'demo-season-2-1',
        Name: 'Season 1',
        IndexNumber: 1,
        SeriesId: 'demo-series-2',
        Episodes: [
          {
            Id: 'demo-s2-ep1',
            Name: 'Blazing Trails',
            Type: 'Episode',
            IndexNumber: 1,
            ParentIndexNumber: 1,
            SeriesName: 'Streamline 4K Cinema',
            SeriesId: 'demo-series-2',
            SeasonName: 'Season 1',
            SeasonId: 'demo-season-2-1',
            RunTimeTicks: 150000000,
            Overview: 'Extreme action sports captured with ultra high speed cameras.',
            streamUrl: GTV + 'ForBiggerBlazes.mp4',
            posterUrl: GTV + 'images/ForBiggerBlazes.jpg',
            UserData: { Played: false }
          },
          {
            Id: 'demo-s2-ep2',
            Name: 'Euphoric Escapes',
            Type: 'Episode',
            IndexNumber: 2,
            ParentIndexNumber: 1,
            SeriesName: 'Streamline 4K Cinema',
            SeriesId: 'demo-series-2',
            SeasonName: 'Season 1',
            SeasonId: 'demo-season-2-1',
            RunTimeTicks: 180000000,
            Overview: 'Breath-taking landscapes and vistas in crystal clear 4K.',
            streamUrl: GTV + 'ForBiggerFun.mp4',
            posterUrl: GTV + 'images/ForBiggerFun.jpg',
            UserData: { Played: false }
          }
        ]
      }
    ]
  }
];

export const DEMO_ALL_ITEMS: JellyfinItem[] = [
  ...DEMO_MOVIES,
  ...DEMO_SHOWS
];
