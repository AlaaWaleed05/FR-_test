import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import type { TableProps } from 'antd';
import { Alert, Button, DatePicker, Input, Select, Space, Table, Tag, Typography } from 'antd';
import dayjs, { type Dayjs } from 'dayjs';
import { searchProfiles } from '../api/profiles';
import { useReferenceList } from '../api/reference';
import { ApiError } from '../api/http';
import type {
  ProfileListSortField,
  ProfileProvenance,
  ProfileStatus,
  ProfileSummaryResponse,
  SortOrder,
} from '../api/types';
import {
  PROVENANCE_COLORS,
  PROVENANCE_LABELS_AR,
  PROVENANCE_OPTIONS,
  STATUS_COLORS,
  STATUS_LABELS_AR,
  STATUS_OPTIONS,
} from './statusLabels';

const COLUMN_KEY_TO_SORT_FIELD: Record<string, ProfileListSortField> = {
  accountNumber: 'ACCOUNT_NUMBER',
  branchCode: 'BRANCH',
  status: 'STATUS',
  submittedAt: 'SUBMITTED_AT',
};

function sortOrderFor(
  columnKey: string,
  sortField: ProfileListSortField,
  sortOrder: SortOrder,
): 'ascend' | 'descend' | null {
  if (COLUMN_KEY_TO_SORT_FIELD[columnKey] !== sortField) return null;
  return sortOrder === 'ASC' ? 'ascend' : 'descend';
}

interface Filters {
  status?: ProfileStatus;
  provenance?: ProfileProvenance;
  branchCode?: string;
  rejectionReasonCode?: string;
  submittedFrom?: string;
  submittedTo?: string;
}

/**
 * operator.md's primary working surface. All filtering, sorting, searching and pagination
 * happens on the server (`ProfileListController`) -- this component only ever renders the page
 * the server already decided (docs/components/backoffice-components.md, "Table -- the standing
 * rule"). Filters live in a toolbar above the table, not antd's per-column filter menus: a date
 * range doesn't fit that pattern, and operator.md itself treats "search" and "filter" as two
 * distinct actions.
 */
export default function ProfileListPage(): React.JSX.Element {
  const navigate = useNavigate();
  const [filters, setFilters] = useState<Filters>({});
  const [searchText, setSearchText] = useState('');
  const [debouncedSearchText, setDebouncedSearchText] = useState('');
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [sortField, setSortField] = useState<ProfileListSortField>('SUBMITTED_AT');
  const [sortOrder, setSortOrder] = useState<SortOrder>('DESC');

  const [rows, setRows] = useState<ProfileSummaryResponse[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const branchList = useReferenceList('branch');
  const rejectionReasonList = useReferenceList('rejection_reason');
  /**
   * NOT filtered on `isActive`, and that is the opposite of `RejectModal` on purpose.
   *
   * That component is a picker for a NEW rejection, so it must not offer a withdrawn code. This one
   * searches rejections that ALREADY HAPPENED. Hiding REJ-03 «فشل أو عدم وضوح مطابقة الوجه»
   * (withdrawn 2026-09-16, BL-154, V0074) would make every profile already rejected under it
   * unfindable — precisely the outcome V0074 chose the `is_active` flag over a new list version to
   * avoid. A withdrawn reason stays searchable for exactly as long as profiles carry it.
   */
  const rejectionReasonOptions = useMemo(
    () => rejectionReasonList.items.map((item) => ({ value: item.itemCode, label: item.labelAr })),
    [rejectionReasonList.items],
  );

  const branchLabelByCode = useMemo(() => {
    const map = new Map<string, string>();
    for (const item of branchList.items) map.set(item.itemCode, item.labelAr);
    return map;
  }, [branchList.items]);

  useEffect(() => {
    // Guarded, not unconditional: this effect also runs on mount, so an unconditional
    // setPage(1) here fired 400ms after every load regardless of whether the search text had
    // actually changed -- silently snapping an operator back to page 1 if they paginated within
    // that window. Found under review (the second pass, in the first pass's own fix for the
    // original missing-reset bug).
    if (searchText === debouncedSearchText) return;
    const timer = setTimeout(() => {
      setDebouncedSearchText(searchText);
      setPage(1);
    }, 400);
    return () => clearTimeout(timer);
  }, [searchText, debouncedSearchText]);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    searchProfiles({
      ...filters,
      q: debouncedSearchText || undefined,
      sortField,
      sortOrder,
      page,
      pageSize,
    })
      .then((response) => {
        if (cancelled) return;
        setRows(response.rows);
        setTotal(response.total);
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setError(err instanceof ApiError ? `تعذر تحميل القائمة (${err.status})` : 'تعذر تحميل القائمة');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [filters, debouncedSearchText, sortField, sortOrder, page, pageSize]);

  const updateFilter = <K extends keyof Filters>(key: K, value: Filters[K]) => {
    setFilters((prev) => ({ ...prev, [key]: value }));
    setPage(1);
  };

  const onDateRangeChange = (range: [Dayjs | null, Dayjs | null] | null) => {
    setFilters((prev) => ({
      ...prev,
      submittedFrom: range?.[0] ? range[0].startOf('day').toISOString() : undefined,
      submittedTo: range?.[1] ? range[1].endOf('day').toISOString() : undefined,
    }));
    setPage(1);
  };

  const onTableChange: TableProps<ProfileSummaryResponse>['onChange'] = (pagination, _tableFilters, sorter) => {
    setPage(pagination.current ?? 1);
    setPageSize(pagination.pageSize ?? 10);
    const single = Array.isArray(sorter) ? sorter[0] : sorter;
    if (single?.order && typeof single.columnKey === 'string') {
      const field = COLUMN_KEY_TO_SORT_FIELD[single.columnKey];
      if (field) {
        setSortField(field);
        setSortOrder(single.order === 'ascend' ? 'ASC' : 'DESC');
      }
    }
  };

  const columns: TableProps<ProfileSummaryResponse>['columns'] = [
    {
      key: 'accountNumber',
      dataIndex: 'accountNumber',
      title: 'رقم الحساب',
      sorter: true,
      sortOrder: sortOrderFor('accountNumber', sortField, sortOrder),
    },
    {
      key: 'displayName',
      title: 'الاسم',
      render: (_, record) => record.displayNameAr ?? record.displayNameEn ?? '—',
    },
    {
      key: 'branchCode',
      dataIndex: 'branchCode',
      title: 'الفرع',
      sorter: true,
      sortOrder: sortOrderFor('branchCode', sortField, sortOrder),
      render: (branchCode: string) => branchLabelByCode.get(branchCode) ?? branchCode,
    },
    {
      key: 'status',
      dataIndex: 'status',
      title: 'الحالة',
      sorter: true,
      sortOrder: sortOrderFor('status', sortField, sortOrder),
      render: (status: ProfileStatus) => <Tag color={STATUS_COLORS[status]}>{STATUS_LABELS_AR[status]}</Tag>,
    },
    {
      key: 'provenance',
      dataIndex: 'provenance',
      title: 'المصدر',
      render: (provenance: ProfileProvenance) => (
        <Tag color={PROVENANCE_COLORS[provenance]}>{PROVENANCE_LABELS_AR[provenance]}</Tag>
      ),
    },
    {
      key: 'submittedAt',
      dataIndex: 'submittedAt',
      title: 'تاريخ التقديم',
      sorter: true,
      sortOrder: sortOrderFor('submittedAt', sortField, sortOrder),
      defaultSortOrder: 'descend',
      // <bdi> isolates the date's own (LTR) bidi direction from the surrounding RTL row -- an
      // all-digit/punctuation string like "DD/MM/YYYY HH:mm" has no strong-direction character to
      // anchor it otherwise, and the browser visually reorders it to "HH:mm DD/MM/YYYY". Found live
      // in S6-02's own Playwright proof of the single-profile view, which formats dates the same
      // way -- the same defect, just not previously screenshotted on this page.
      // D4.2: `DD/MM/YYYY HH:mm`, never ISO. Found by the 2026-09-07 baseline review: the design
      // plan's date rider named only ProfileDetailPage, but this list is the screen operators
      // spend most of their time on, so fixing only the detail page would have left the rule
      // half-applied in the very tier it was written for.
      render: (submittedAt: string | null) =>
        submittedAt ? <bdi>{dayjs(submittedAt).format('DD/MM/YYYY HH:mm')}</bdi> : '—',
    },
  ];

  return (
    <div>
      <Typography.Title level={3}>الملفات</Typography.Title>
      <Space wrap style={{ marginBottom: 16 }}>
        <Select
          allowClear
          placeholder="الحالة"
          style={{ width: 200 }}
          options={STATUS_OPTIONS}
          value={filters.status}
          onChange={(value) => updateFilter('status', value)}
        />
        <Select
          allowClear
          placeholder="المصدر"
          style={{ width: 140 }}
          options={PROVENANCE_OPTIONS}
          value={filters.provenance}
          onChange={(value) => updateFilter('provenance', value)}
        />
        <Select
          allowClear
          showSearch
          placeholder="الفرع"
          style={{ width: 200 }}
          loading={branchList.loading}
          optionFilterProp="label"
          options={branchList.items.map((item) => ({ value: item.itemCode, label: item.labelAr }))}
          value={filters.branchCode}
          onChange={(value) => updateFilter('branchCode', value)}
        />
        <Select
          allowClear
          showSearch
          placeholder="سبب الرفض"
          style={{ width: 240 }}
          loading={rejectionReasonList.loading}
          optionFilterProp="label"
          options={rejectionReasonOptions}
          value={filters.rejectionReasonCode}
          onChange={(value) => updateFilter('rejectionReasonCode', value)}
        />
        <DatePicker.RangePicker onChange={onDateRangeChange} />
        <Input.Search
          allowClear
          placeholder="بحث في جميع الحقول"
          style={{ width: 260 }}
          value={searchText}
          onChange={(e) => setSearchText(e.target.value)}
          onSearch={(value) => {
            setDebouncedSearchText(value);
            setPage(1);
          }}
        />
      </Space>
      {(branchList.error || rejectionReasonList.error) && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
          message="تعذر تحميل قوائم الفروع أو أسباب الرفض. القائمة نفسها لا تزال تعمل، لكن هذه المرشّحات معطّلة مؤقتًا."
          action={
            <Button
              size="small"
              onClick={() => {
                branchList.retry();
                rejectionReasonList.retry();
              }}
            >
              إعادة المحاولة
            </Button>
          }
        />
      )}
      {error && <Alert type="error" message={error} showIcon style={{ marginBottom: 16 }} />}
      <Table<ProfileSummaryResponse>
        rowKey="profileId"
        columns={columns}
        dataSource={rows}
        loading={loading}
        onChange={onTableChange}
        onRow={(record) => ({
          onClick: () => navigate(`/profiles/${record.profileId}`),
          style: { cursor: 'pointer' },
        })}
        pagination={{
          current: page,
          pageSize,
          total,
          showSizeChanger: true,
          // Invariant phrasing -- never a counted noun form (CLAUDE.md: Arabic numeral
          // agreement differs for 1/2/3-10/11+; a template with no agreed noun sidesteps it
          // entirely, same convention as ContactChannelsScreen on mobile).
          showTotal: () => `الإجمالي: ${total}`,
        }}
      />
    </div>
  );
}
