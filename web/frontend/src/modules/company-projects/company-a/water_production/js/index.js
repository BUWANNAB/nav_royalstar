import axiosClient from "../../../../../api/ApiManager.js";
const itemsPerPage = 8; // 每页显示的条目数
let currentPage = 1;
let allData = [];
// 按时间排序数据
function sortDataByTime(data) {
    return data.sort((a, b) => new Date(b.createTime) - new Date(a.createTime));
}

// 格式化时间显示，移除T字符
function formatDateTime(dateString) {
    const date = new Date(dateString);
    const year = date.getFullYear();
    const month = String(date.getMonth() + 1).padStart(2, '0');
    const day = String(date.getDate()).padStart(2, '0');
    const hours = String(date.getHours()).padStart(2, '0');
    const minutes = String(date.getMinutes()).padStart(2, '0');
    const seconds = String(date.getSeconds()).padStart(2, '0');
    return `${year}-${month}-${day} ${hours}:${minutes}:${seconds}`;
}
// 填充表格数据
function populateTable(data) {
    const sortedData = sortDataByTime(data);
    const infoList = document.getElementById('infoList');
    infoList.innerHTML = ''; // 清空现有内容
    const start = (currentPage - 1) * itemsPerPage;
    const end = start + itemsPerPage;
    const paginatedData = sortedData.slice(start, end);

    paginatedData.forEach(item => {
        const row = document.createElement('tr');
        row.innerHTML = `
            <td>${item.stationName}</td>
            <td>${item.o2}</td>
            <td>${item.ph}</td>
            <td>${item.temp}</td>
            <td>${formatDateTime(item.createTime)}</td>
        `;
        infoList.appendChild(row);
    });
    // 更新分页器
    updatePagination(sortedData.length);
}

// 初始化表格
function initializeTable() {
    getAllData().then(data => {
        allData = data;
        populateTable(allData);
    });
    setInitDate()
}
// 显示站点的最新数据
function displayLatestData() {
    const siteName = document.getElementById('siteNameFilter').value.trim().toLowerCase();
    const filteredData = allData.filter(item => item.stationName.toLowerCase() === siteName);

    if (filteredData.length > 0) {
        const latestData = sortDataByTime(filteredData)[0];
        document.getElementById('currentStationName').textContent = `${latestData.stationName}`;
        document.getElementById('currentO2').textContent = `${latestData.o2}%`;
        document.getElementById('currentPh').textContent = `${latestData.ph}`;
        document.getElementById('currentTemp').textContent = `${latestData.temp}°C`;
        document.getElementById('currentTime').textContent = `${formatDateTime(latestData.createTime)}`;
    } else {
        document.getElementById('currentStationName').textContent = '请输入站点';
        document.getElementById('currentO2').textContent = '';
        document.getElementById('currentPh').textContent = '';
        document.getElementById('currentTemp').textContent = '';
        document.getElementById('currentTime').textContent = '';
    }
}

// 应用筛选条件
function applyFilters2() {
    const siteName = document.getElementById('siteNameFilter2').value.trim().toLowerCase();
    const time = document.getElementById('timeFilter2').value;
    const filteredData = allData.filter(item => {
        const stationNameMatch = siteName === "" || item.stationName.toLowerCase().includes(siteName);
        const timeMatch = time === "" || new Date(item.createTime).toDateString() === new Date(time).toDateString();
        return stationNameMatch && timeMatch;
    });

    currentPage = 1; // 重置到第一页
    populateTable(filteredData);
}

// 重置筛选条件
function resetFilters2() {
    document.getElementById('siteNameFilter2').value = '';
    document.getElementById('timeFilter2').value = '';
    currentPage = 1; // 重置到第一页
    populateTable(allData);
    setInitDate();
}

// 更新分页器
function updatePagination(totalItems) {
    const pagination = document.getElementById('pagination');
    pagination.innerHTML = ''; // 清空现有分页按钮

    const totalPages = Math.ceil(totalItems / itemsPerPage);
    if (totalPages <= 1) return;

    // 添加"上一页"按钮
    const prevLi = document.createElement('li');
    prevLi.className = `page-item ${currentPage === 1 ? 'disabled' : ''}`;
    const prevA = document.createElement('a');
    prevA.className = 'page-link';
    prevA.href = '#';
    prevA.textContent = '上一页';
    prevA.onclick = () => {
        if (currentPage > 1) {
            currentPage--;
            populateTable(allData);
        }
    };
    prevLi.appendChild(prevA);
    pagination.appendChild(prevLi);

    // 智能折叠页码
    const maxVisible = 7; // 最多显示的页码按钮数
    let startPage = Math.max(1, currentPage - 3);
    let endPage = Math.min(totalPages, currentPage + 3);

    // 调整起始和结束页码，确保显示7个按钮
    if (endPage - startPage + 1 < maxVisible) {
        if (currentPage <= 4) {
            endPage = Math.min(totalPages, maxVisible);
        } else if (currentPage >= totalPages - 3) {
            startPage = Math.max(1, totalPages - maxVisible + 1);
        }
    }

    // 添加第一页
    if (startPage > 1) {
        const firstLi = document.createElement('li');
        firstLi.className = 'page-item';
        const firstA = document.createElement('a');
        firstA.className = 'page-link';
        firstA.href = '#';
        firstA.textContent = 1;
        firstA.onclick = () => {
            currentPage = 1;
            populateTable(allData);
        };
        firstLi.appendChild(firstA);
        pagination.appendChild(firstLi);

        if (startPage > 2) {
            const ellipsis1 = document.createElement('li');
            ellipsis1.className = 'page-item disabled';
            ellipsis1.innerHTML = '<a class="page-link" href="#">...</a>';
            pagination.appendChild(ellipsis1);
        }
    }

    // 添加中间页码
    for (let i = startPage; i <= endPage; i++) {
        const li = document.createElement('li');
        li.className = `page-item ${i === currentPage ? 'active' : ''}`;
        const a = document.createElement('a');
        a.className = 'page-link';
        a.href = '#';
        a.textContent = i;
        a.onclick = () => {
            currentPage = i;
            populateTable(allData);
        };
        li.appendChild(a);
        pagination.appendChild(li);
    }

    // 添加最后一页
    if (endPage < totalPages) {
        if (endPage < totalPages - 1) {
            const ellipsis2 = document.createElement('li');
            ellipsis2.className = 'page-item disabled';
            ellipsis2.innerHTML = '<a class="page-link" href="#">...</a>';
            pagination.appendChild(ellipsis2);
        }

        const lastLi = document.createElement('li');
        lastLi.className = 'page-item';
        const lastA = document.createElement('a');
        lastA.className = 'page-link';
        lastA.href = '#';
        lastA.textContent = totalPages;
        lastA.onclick = () => {
            currentPage = totalPages;
            populateTable(allData);
        };
        lastLi.appendChild(lastA);
        pagination.appendChild(lastLi);
    }

    // 添加"下一页"按钮
    const nextLi = document.createElement('li');
    nextLi.className = `page-item ${currentPage === totalPages ? 'disabled' : ''}`;
    const nextA = document.createElement('a');
    nextA.className = 'page-link';
    nextA.href = '#';
    nextA.textContent = '下一页';
    nextA.onclick = () => {
        if (currentPage < totalPages) {
            currentPage++;
            populateTable(allData);
        }
    };
    nextLi.appendChild(nextA);
    pagination.appendChild(nextLi);
}

// 导出Excel
function exportToExcel() {
    const siteName = document.getElementById('siteNameFilter2').value.trim().toLowerCase();
    const time = document.getElementById('timeFilter2').value;
    const filteredData = allData.filter(item => {
        const stationNameMatch = siteName === "" || item.stationName.toLowerCase().includes(siteName);
        const timeMatch = time === "" || new Date(item.createTime).toDateString() === new Date(time).toDateString();
        return stationNameMatch && timeMatch;
    });

    const sortedData = sortDataByTime(filteredData);
    const ws = XLSX.utils.json_to_sheet(sortedData, { header: ["stationName", "o2", "ph", "temp", "createTime"] });
    const wb = XLSX.utils.book_new();
    XLSX.utils.book_append_sheet(wb, ws, "Sheet1");
    XLSX.writeFile(wb, "water_quality_data.xlsx");
}

// 获取所有数据
async function getAllData() {
    try {
        const response = await axiosClient.get("sensor/queryalldata");
        console.log(response, "????res");
        const flag = response.data.code;
        if (flag === 0) {
            return response.data.data;
        } else {
            console.error("Failed to fetch data");
            return [];
        }
    } catch (error) {
        console.error("Error fetching data:", error);
        return [];
    }
}
function setInitDate(){
    const currentDate = new Date().toISOString().split('T')[0];
    document.getElementById("timeFilter2").value = currentDate
}
// 初始化表格数据
initializeTable();
// 绑定事件
document.getElementById('queryButton').addEventListener('click', displayLatestData);
document.getElementById('filterButton').addEventListener('click', applyFilters2);
document.getElementById('resetButton').addEventListener('click', resetFilters2);
document.getElementById('exportButton').addEventListener('click', exportToExcel);
setInitDate()