export interface ApiEnvelope<T> { success: boolean; data: T; requestId: string }
export interface PageResult<T> { items?: T[]; records?: T[]; total: number; page: number; pageSize: number }
export interface UserView { publicId: string; username: string; email: string; timezone: string; emailVerified: boolean; roles: string[]; permissions: string[]; version: number }
export interface TokenPair { accessToken: string; refreshToken: string; expiresIn: number; user: UserView }
export type EmailVerificationPurpose = 'REGISTER' | 'PASSWORD_RESET'
export interface EmailCodeDelivery { expiresInSeconds: number; resendAfterSeconds: number }
export interface Entity { id?: number; publicId: string; version: number; createdAt?: string; updatedAt?: string; [key: string]: unknown }

// ---- 图书（微信读书）契约 ----
export interface BookView {
  bookId: string
  title: string
  author: string | null
  coverUrl: string | null
  category: string | null
  categoryId: string | null
  readingProgress: number // 0~1
  isFinished: boolean
  status: 'reading' | 'finished' | 'unread'
  updateTime: string | null
  lastReadChapter: string | null
  wordCount: number | null
  format: string | null
  type: 'book' | 'audiobook'
  isPublic: boolean
  deepLink: string | null
}

export interface BookShelfView {
  total: number
  readingCount: number
  finishedCount: number
  books: BookView[]
}

export type QrLoginStatus = 'PENDING' | 'SCANNED' | 'SUCCESS' | 'EXPIRED' | 'FAILED'

export interface BookQrLoginView {
  status: QrLoginStatus
  qrBase64: string | null
  qrToken: string | null
  message: string | null
  expiresAt: string | null
}

export interface BookLoginStatusView {
  loggedIn: boolean
  loginType: 'QR_CODE' | 'API_KEY' | null
  nickname: string | null
  headImgUrl: string | null
  isVip: boolean
  lastLoginAt: string | null
  loginQr: BookQrLoginView | null
}

export interface RatingDetailView {
  good: number | null
  fair: number | null
  poor: number | null
  recent: number | null
  deepV: number | null
  myRating: string | null
}

export interface BookInfoView {
  bookId: string
  title: string | null
  author: string | null
  coverUrl: string | null
  category: string | null
  intro: string | null
  publisher: string | null
  publishTime: string | null
  isbn: string | null
  translator: string | null
  wordCount: number | null
  newRating: number | null
  newRatingCount: number | null
  ratingDetail: RatingDetailView | null
  deepLink: string | null
}

export interface BookProgressView {
  bookId: string
  progressPercent: number | null
  chapterIdx: number | null
  chapterUid: number | null
  readingTime: number | null
  updateTime: string | null
  lastReadAt: string | null
}

export interface BookIntroView {
  bookId: string
  title: string | null
  author: string | null
  coverUrl: string | null
  category: string | null
  intro: string | null
  price: number | null
  format: string | null
  type: 'book' | 'audiobook'
  deepLink: string | null
}

export interface ReadPreferBookView {
  bookId: string | null
  title: string | null
  cover: string | null
  type: number | null
}

export interface ReadMedalView {
  name: string | null
  displayText: string | null
  rankText: string | null
}

export type ReadDataMode = 'weekly' | 'monthly' | 'annually' | 'overall'

export interface ReadDataDetailView {
  totalReadTime: number | null
  wrReadTime: number | null
  wrListenTime: number | null
  readDays: number | null
  readRate: number | null
  registTime: string | null
  preferCategoryWord: string | null
  preferTimeWord: string | null
  preferBooks: ReadPreferBookView[]
  medals: ReadMedalView[]
}
