"""图书域契约（与 weread-mcp / Java 共用，camelCase 别名）。"""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

QrStatus = Literal["PENDING", "SCANNED", "SUCCESS", "EXPIRED", "FAILED"]


class BookView(BaseModel):
    model_config = ConfigDict(extra="ignore", populate_by_name=True)

    book_id: str = Field(alias="bookId", min_length=1)
    title: str = Field(alias="title")
    author: str | None = Field(default=None, alias="author")
    cover_url: str | None = Field(default=None, alias="coverUrl")
    category: str | None = Field(default=None, alias="category")
    category_id: str | None = Field(default=None, alias="categoryId")
    reading_progress: float = Field(default=0.0, alias="readingProgress", ge=0, le=1)
    is_finished: bool = Field(default=False, alias="isFinished")
    status: Literal["reading", "finished", "unread"] = Field(default="unread", alias="status")
    update_time: str | None = Field(default=None, alias="updateTime")
    last_read_chapter: str | None = Field(default=None, alias="lastReadChapter")
    word_count: int | None = Field(default=None, alias="wordCount")
    format: str | None = Field(default=None, alias="format")
    type: Literal["book", "audiobook"] = Field(default="book", alias="type")
    is_public: bool = Field(default=False, alias="isPublic")
    deep_link: str | None = Field(default=None, alias="deepLink")


class BookShelfView(BaseModel):
    model_config = ConfigDict(extra="ignore", populate_by_name=True)

    total: int = Field(alias="total", ge=0)
    reading_count: int = Field(alias="readingCount", ge=0)
    finished_count: int = Field(alias="finishedCount", ge=0)
    books: list[BookView] = Field(alias="books")


class BookQrLoginView(BaseModel):
    model_config = ConfigDict(extra="ignore", populate_by_name=True)

    status: QrStatus = Field(alias="status")
    qr_base64: str | None = Field(default=None, alias="qrBase64")
    qr_token: str | None = Field(default=None, alias="qrToken")
    message: str | None = Field(default=None, alias="message")
    expires_at: str | None = Field(default=None, alias="expiresAt")


class BookLoginStatusView(BaseModel):
    model_config = ConfigDict(extra="ignore", populate_by_name=True)

    logged_in: bool = Field(alias="loggedIn")
    login_type: Literal["QR_CODE", "API_KEY"] | None = Field(default=None, alias="loginType")
    nickname: str | None = Field(default=None, alias="nickname")
    head_img_url: str | None = Field(default=None, alias="headImgUrl")
    is_vip: bool = Field(default=False, alias="isVip")
    last_login_at: str | None = Field(default=None, alias="lastLoginAt")
    login_qr: BookQrLoginView | None = Field(default=None, alias="loginQr")


class SetApiKeyRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    api_key: str = Field(alias="apiKey", min_length=4, max_length=128)


class RatingDetailView(BaseModel):
    model_config = ConfigDict(extra="ignore", populate_by_name=True)

    good: int | None = Field(default=None, alias="good")
    fair: int | None = Field(default=None, alias="fair")
    poor: int | None = Field(default=None, alias="poor")
    recent: int | None = Field(default=None, alias="recent")
    deep_v: int | None = Field(default=None, alias="deepV")
    my_rating: str | None = Field(default=None, alias="myRating")


class BookInfoView(BaseModel):
    model_config = ConfigDict(extra="ignore", populate_by_name=True)

    book_id: str = Field(alias="bookId", min_length=1)
    title: str | None = Field(default=None, alias="title")
    author: str | None = Field(default=None, alias="author")
    cover_url: str | None = Field(default=None, alias="coverUrl")
    category: str | None = Field(default=None, alias="category")
    intro: str | None = Field(default=None, alias="intro")
    publisher: str | None = Field(default=None, alias="publisher")
    publish_time: str | None = Field(default=None, alias="publishTime")
    isbn: str | None = Field(default=None, alias="isbn")
    translator: str | None = Field(default=None, alias="translator")
    word_count: int | None = Field(default=None, alias="wordCount")
    new_rating: float | None = Field(default=None, alias="newRating")
    new_rating_count: int | None = Field(default=None, alias="newRatingCount")
    rating_detail: RatingDetailView | None = Field(default=None, alias="ratingDetail")
    deep_link: str | None = Field(default=None, alias="deepLink")


class BookProgressView(BaseModel):
    model_config = ConfigDict(extra="ignore", populate_by_name=True)

    book_id: str = Field(alias="bookId", min_length=1)
    progress_percent: int | None = Field(default=None, alias="progressPercent", ge=0, le=100)
    chapter_idx: int | None = Field(default=None, alias="chapterIdx")
    chapter_uid: int | None = Field(default=None, alias="chapterUid")
    reading_time: int | None = Field(default=None, alias="readingTime")
    update_time: str | None = Field(default=None, alias="updateTime")
    last_read_at: str | None = Field(default=None, alias="lastReadAt")


class BookIntroView(BaseModel):
    model_config = ConfigDict(extra="ignore", populate_by_name=True)

    book_id: str = Field(alias="bookId", min_length=1)
    title: str | None = Field(default=None, alias="title")
    author: str | None = Field(default=None, alias="author")
    cover_url: str | None = Field(default=None, alias="coverUrl")
    category: str | None = Field(default=None, alias="category")
    intro: str | None = Field(default=None, alias="intro")
    price: float | None = Field(default=None, alias="price")
    format: str | None = Field(default=None, alias="format")
    type: Literal["book", "audiobook"] = Field(default="book", alias="type")
    deep_link: str | None = Field(default=None, alias="deepLink")


class ReadPreferBookView(BaseModel):
    model_config = ConfigDict(extra="ignore", populate_by_name=True)

    book_id: str | None = Field(default=None, alias="bookId")
    title: str | None = Field(default=None, alias="title")
    cover: str | None = Field(default=None, alias="cover")
    type: int | None = Field(default=None, alias="type")


class ReadMedalView(BaseModel):
    model_config = ConfigDict(extra="ignore", populate_by_name=True)

    name: str | None = Field(default=None, alias="name")
    display_text: str | None = Field(default=None, alias="displayText")
    rank_text: str | None = Field(default=None, alias="rankText")


class ReadDataDetailView(BaseModel):
    model_config = ConfigDict(extra="ignore", populate_by_name=True)

    total_read_time: int | None = Field(default=None, alias="totalReadTime")
    wr_read_time: int | None = Field(default=None, alias="wrReadTime")
    wr_listen_time: int | None = Field(default=None, alias="wrListenTime")
    read_days: int | None = Field(default=None, alias="readDays")
    read_rate: int | None = Field(default=None, alias="readRate")
    regist_time: str | None = Field(default=None, alias="registTime")
    prefer_category_word: str | None = Field(default=None, alias="preferCategoryWord")
    prefer_time_word: str | None = Field(default=None, alias="preferTimeWord")
    prefer_books: list[ReadPreferBookView] = Field(default_factory=list, alias="preferBooks")
    medals: list[ReadMedalView] = Field(default_factory=list, alias="medals")
