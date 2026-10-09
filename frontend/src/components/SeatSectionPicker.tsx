import type { SeatSectionSummaryResponse } from '../types';

interface SeatSectionPickerProps {
  disabled: boolean;
  sections: SeatSectionSummaryResponse[];
  selectedSection: string | null;
  onSelectSection: (section: string) => void;
}

export function SeatSectionPicker({ disabled, sections, selectedSection, onSelectSection }: SeatSectionPickerProps) {
  if (sections.length === 0) {
    return <p className="empty-copy">표시할 예매 가능 구역이 없습니다.</p>;
  }

  return (
    <div className="section-picker" aria-label="좌석 구역">
      {sections.map((section) => (
        <button
          className={section.section === selectedSection ? 'section-button selected' : 'section-button'}
          key={section.section}
          type="button"
          disabled={disabled}
          onClick={() => onSelectSection(section.section)}
        >
          {section.section}
          <small>{section.availableCount}석</small>
        </button>
      ))}
    </div>
  );
}
