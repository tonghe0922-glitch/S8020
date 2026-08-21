export type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger'
export type ButtonSize = 'sm' | 'md' | 'lg'
export type CardVariant = 'default' | 'muted' | 'spotlight'
export type StatusTone = 'neutral' | 'info' | 'success' | 'warning' | 'danger'
export type ToastTone = StatusTone
export type DrawerSide = 'left' | 'right' | 'bottom'
export type DateTimeMode = 'date' | 'time' | 'datetime'
export type AvatarSize = 'sm' | 'md' | 'lg'
export type KpiTone = 'neutral' | 'danger'

export interface SelectOption {
  value: string
  label: string
  disabled?: boolean
}

export interface CascaderOption extends SelectOption {
  children?: readonly CascaderOption[]
}

export interface ToastItem {
  id: string
  tone?: ToastTone
  title: string
  message?: string
  dismissible?: boolean
}
