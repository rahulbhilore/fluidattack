export const constantPrefix = "CONTEXT_";

export const CONTEXT_SHOW = `${constantPrefix}SHOW`;
export const CONTEXT_SHOW_MULTIPLE = `${constantPrefix}SHOW_MULTIPLE`;
export const CONTEXT_HIDE = `${constantPrefix}HIDE`;
export const CONTEXT_SELECT = `${constantPrefix}SELECT`;
export const CONTEXT_MOVE = `${constantPrefix}MOVE`;

// This is offset from cursor position to top left corner
// basically top = e.y + OFFSET, left=e.x + OFFSET
// not sure where it's better to store
export const CONTEXT_OFFSET = 3;
